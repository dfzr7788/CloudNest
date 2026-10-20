"""Polling sync prototype. SQLite records remote state, local baselines and durable operations.

Conflict data is retained as ordinary sibling files. Removed clean data goes to local trash.
There is no permanent-delete, arbitrary shell, provider key or automatic conflict winner API.
"""
import hashlib
from contextlib import closing
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import stat
import time
import unicodedata
import uuid

from transport import SyncError


def packed(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def publish_no_replace(source, target):
    # Windows rename is atomic and refuses an existing destination. POSIX link gives
    # equivalent no-replace semantics for the immutable private download stage.
    if os.name == "nt":
        os.rename(source, target)
    else:
        os.link(source, target)
        source.unlink()


def component(name):
    if (not isinstance(name, str) or not name or name in (".", "..") or
            any(ord(c) < 32 or c in '/\\:*?"<>|' for c in name) or
            name.endswith((".", " ")) or len(name.encode("utf-16-le")) > 510 or
            name.casefold().startswith(".cloudnest-")):
        raise ValueError("A name cannot be represented safely on Windows")
    stem = name.split(".")[0].upper()
    if stem in {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"} or re.fullmatch(r"(?:COM|LPT)[1-9¹²³]", stem):
        raise ValueError("Reserved Windows device name")
    return name


def path_key(path):
    return unicodedata.normalize("NFC", path).casefold()


class Engine:
    def __init__(self, root, api):
        self.root, self.api = Path(root).absolute(), api
        if os.name == "nt" and str(self.root).startswith("\\\\"):
            raise ValueError("Use a local Windows directory; UNC state databases are unsupported")
        self.root.mkdir(parents=True, exist_ok=True)
        self.check(self.root)
        self.home = self.root / ".cloudnest-sync"
        self.check(self.home)
        marker = self.check(self.home / "format")
        if self.home.exists():
            if not marker.is_file() or marker.read_text(encoding="utf-8") != "CloudNest sync state v1\n":
                raise ValueError("Reserved state directory already contains unrelated data")
        else:
            self.home.mkdir()
            marker.write_text("CloudNest sync state v1\n", encoding="utf-8")
        self.lock = (self.home / "lock").open("a+b")
        self.lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                if not self.lock.read(1):
                    self.lock.write(b"0")
                    self.lock.flush()
                self.lock.seek(0)
                msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.lock.close()
            raise RuntimeError("Another sync process is using this folder") from None
        try:
            self.check(self.home / "state.sqlite3")
            self.db = sqlite3.connect(self.home / "state.sqlite3")
            self.db.row_factory = sqlite3.Row
            self.db.executescript("""
              PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
              CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS remote(id TEXT PRIMARY KEY,payload TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS baseline(id TEXT PRIMARY KEY,payload TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS pending(signature TEXT PRIMARY KEY,operation_id TEXT NOT NULL,
                payload TEXT NOT NULL,result TEXT);
              CREATE TABLE IF NOT EXISTS conflict(id TEXT PRIMARY KEY,path TEXT NOT NULL,reason TEXT NOT NULL,
                created_at INTEGER NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0);
            """)
            if self.db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                raise RuntimeError("Local state is corrupt; retain files and restore the state backup")
            if "reviewed" not in {row[1] for row in self.db.execute("PRAGMA table_info(conflict)")}:
                self.db.execute("ALTER TABLE conflict ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 0")
            self.db.execute("CREATE INDEX IF NOT EXISTS conflict_reviewed_time ON conflict(reviewed,created_at)")
            self.db.commit()
            self.root_id = self.setting("rootId")
        except BaseException:
            if hasattr(self, "db"):
                self.db.close()
            self.lock.close()
            raise

    def close(self):
        if not getattr(self, "closed", False):
            self.db.close()
            self.lock.close()
            self.closed = True

    def check(self, path):
        """Reject symlinks/junctions including existing ancestors; never follow cloud paths."""
        for item in (path, *path.parents):
            try:
                info = item.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError("Sync does not follow symbolic links or reparse points")
        return path

    def safe(self, relative):
        parts = PurePosixPath(relative).parts
        if not parts or PurePosixPath(relative).is_absolute():
            raise ValueError("Expected a relative path")
        for part in parts:
            component(part)
        path = self.root.joinpath(*parts)
        if os.name == "nt" and len(str(path)) > 240:
            raise ValueError("Path exceeds the prototype's Windows path limit")
        return self.check(path)

    def setting(self, key, value=None):
        if value is not None:
            self.db.execute("INSERT OR REPLACE INTO settings VALUES(?,?)", (key, value))
            self.db.commit()
            return value
        row = self.db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def table(self, name):
        if name not in ("remote", "baseline"):
            raise ValueError("Unknown state table")
        return {row[0]: json.loads(row[1]) for row in self.db.execute(f"SELECT id,payload FROM {name}")}

    def configure_scope(self, names):
        if self.db.execute("SELECT COUNT(*) FROM baseline").fetchone()[0]:
            raise ValueError("Use a separate local directory to change selective sync after files are tracked")
        remote = self.table("remote")
        selected = []
        for name in names:
            component(name)
            matches = [eid for eid, entry in remote.items() if entry["parentId"] == self.root_id
                       and entry["kind"] == "FOLDER" and entry["name"] == name]
            if len(matches) != 1:
                raise ValueError("Selective sync requires existing top-level cloud folder names")
            selected.append(matches[0])
        self.setting("scope", packed(sorted(set(selected))) if names else "ALL")

    def remote(self):
        entries = self.table("remote")
        scope = self.setting("scope")
        if not scope or scope == "ALL":
            return entries
        selected = set(json.loads(scope))
        for eid in selected & entries.keys():
            if entries[eid]["parentId"] != self.root_id or entries[eid]["kind"] != "FOLDER":
                raise ValueError("A selected folder moved below another folder; review scope before continuing")
        children = {}
        for eid, entry in entries.items():
            children.setdefault(entry["parentId"], []).append(eid)
        queue, allowed = list(selected), set()
        while queue:
            eid = queue.pop()
            if eid in allowed:
                continue
            allowed.add(eid)
            queue.extend(children.get(eid, []))
        return {eid: entry for eid, entry in entries.items() if eid in allowed}

    def conflict(self, relative, reason):
        if self.db.execute("SELECT COUNT(*) FROM conflict WHERE reviewed=0").fetchone()[0] >= 5000:
            raise RuntimeError("Conflict history is full; review it before continuing")
        with self.db:
            self.db.execute("DELETE FROM conflict WHERE reviewed=1 AND created_at<?", (int(time.time()*1000)-30*86400000,))
            self.db.execute("DELETE FROM conflict WHERE id IN (SELECT id FROM conflict WHERE reviewed=1 ORDER BY created_at DESC LIMIT -1 OFFSET 10000)")
            self.db.execute("INSERT INTO conflict(id,path,reason,created_at) VALUES(?,?,?,?)", (str(uuid.uuid4()), relative, reason, int(time.time()*1000)))

    def preserve(self, relative, reason):
        source = self.safe(relative)
        if not source.exists():
            return
        name = source.stem[:70] + ".conflict-" + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8] + source.suffix[:20]
        target = source.with_name(name)
        rel = target.relative_to(self.root).as_posix()
        self.safe(rel)
        self.conflict(rel, reason)  # Commit intent before moving unique local data.
        if target.exists():
            raise RuntimeError("Conflict destination unexpectedly exists")
        os.rename(source, target)

    def trash(self, relative):
        source = self.safe(relative)
        if not source.exists():
            return
        if source.is_dir() and any(source.iterdir()):
            self.preserve(relative, "Cloud removed a directory containing local data")
            return
        trash = self.check(self.root / ".cloudnest-trash")
        trash.mkdir(exist_ok=True)
        target = trash / (str(uuid.uuid4()) + "-" + source.name[:80])
        self.conflict(relative, "Cloud deletion/replacement retained in local trash: " + target.name)
        os.rename(source, target)

    def refresh(self):
        cursor = self.setting("cursor")
        if cursor:
            try:
                while True:
                    import urllib.parse
                    feed = self.api.request("GET", "/sync/client/changes?cursor=" + urllib.parse.quote(cursor))
                    with self.db:
                        for event in feed["events"]:
                            change = event["change"]
                            if change["operation"] == "DELETE":
                                self.db.execute("DELETE FROM remote WHERE id=?", (change["entryId"],))
                            elif change["operation"] == "UPSERT":
                                entry = change["entry"]
                                self.db.execute("INSERT OR REPLACE INTO remote VALUES(?,?)", (entry["id"], packed(entry)))
                            else:
                                raise ValueError("Unknown sync event")
                        cursor = feed["cursor"]
                        self.db.execute("INSERT OR REPLACE INTO settings VALUES('cursor',?)", (cursor,))
                    if not feed["hasMore"]:
                        return
            except SyncError as error:
                if error.status != 410:
                    raise
                # Baselines survive cursor loss, so changed local files are still protected.
        snapshot = self.api.request("GET", "/sync/client/bootstrap")
        if self.root_id and self.root_id != snapshot["rootId"]:
            raise ValueError("This folder belongs to a different cloud account")
        if len(snapshot["entries"]) > 10000:
            raise ValueError("Library exceeds 10000 entries")
        with self.db:
            self.db.execute("DELETE FROM remote")
            for entry in snapshot["entries"]:
                self.db.execute("INSERT INTO remote VALUES(?,?)", (entry["id"], packed(entry)))
            self.db.execute("INSERT OR REPLACE INTO settings VALUES('rootId',?)", (snapshot["rootId"],))
            self.db.execute("INSERT OR REPLACE INTO settings VALUES('cursor',?)", (snapshot["cursor"],))
        self.root_id = snapshot["rootId"]

    def paths(self, remote):
        result, resolving = {}, set()
        def resolve(eid):
            if eid == self.root_id:
                return ""
            if eid in result:
                return result[eid]
            if eid in resolving or eid not in remote:
                raise ValueError("Invalid cloud directory graph")
            entry = remote[eid]
            uuid.UUID(eid)
            if entry["kind"] not in ("FILE", "FOLDER"):
                raise ValueError("Unknown entry kind")
            if entry["kind"] == "FILE" and (not re.fullmatch("[a-f0-9]{64}", entry["sha256"] or "") or
                                            not isinstance(entry["size"], int) or entry["size"] < 0):
                raise ValueError("Invalid cloud file checksum or size")
            parent = entry["parentId"]
            if parent != self.root_id and (parent not in remote or remote[parent]["kind"] != "FOLDER"):
                raise ValueError("Invalid cloud parent")
            resolving.add(eid)
            prefix = resolve(parent)
            value = prefix + "/" + component(entry["name"]) if prefix else component(entry["name"])
            self.safe(value)
            resolving.remove(eid)
            result[eid] = value
            return value
        used = set()
        for eid in remote:
            relative = resolve(eid)
            key = path_key(relative)
            if key in used:
                raise ValueError("Cloud paths collide under Windows case/Unicode rules; rename them in the web app")
            used.add(key)
        return result

    def scan(self):
        result, used = {}, set()
        scope = self.setting("scope")
        prefixes, selected_inodes = None, set()
        if scope and scope != "ALL":
            selected = set(json.loads(scope))
            prefixes = {e["name"] for eid, e in self.remote().items() if eid in selected}
            for eid, base in self.table("baseline").items():
                if eid in selected:
                    prefixes.add(PurePosixPath(base["path"]).parts[0])
                    selected_inodes.add(tuple(base["inode"]))
        for directory, dirs, files in os.walk(self.root, followlinks=False):
            if Path(directory) == self.root:
                dirs[:] = [d for d in dirs if d not in (".cloudnest-sync", ".cloudnest-trash")]
                if prefixes is not None:
                    # A renamed selected directory retains its OS identity.
                    for name in dirs:
                        info = (self.root / name).lstat()
                        if (info.st_dev, info.st_ino) in selected_inodes:
                            prefixes.add(name)
                    dirs[:] = [name for name in dirs if name in prefixes]
                    files = []
            for name in dirs + files:
                path = self.check(Path(directory) / name)
                relative = path.relative_to(self.root).as_posix()
                self.safe(relative)
                info = path.stat()
                if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)) or (stat.S_ISREG(info.st_mode) and info.st_nlink > 1):
                    raise ValueError("Only ordinary files and folders are supported")
                key = path_key(relative)
                if key in used:
                    raise ValueError("Local paths collide under Windows case/Unicode rules")
                used.add(key)
                result[relative] = {"kind": "FOLDER" if path.is_dir() else "FILE", "inode": [info.st_dev, info.st_ino],
                                    "sha256": None if path.is_dir() else digest(path)}
                if len(result) > 10000:
                    raise ValueError("Local directory exceeds 10000 entries")
        return result

    def baseline(self, entry, relative):
        path = self.safe(relative)
        info = path.stat()
        value = {**entry, "path": relative, "inode": [info.st_dev, info.st_ino]}
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO baseline VALUES(?,?)", (entry["id"], packed(value)))

    def prepare(self, kind, values):
        signature = hashlib.sha256(packed([kind, values]).encode()).hexdigest()
        existing = self.db.execute("SELECT * FROM pending WHERE signature=?", (signature,)).fetchone()
        if not existing:
            if self.db.execute("SELECT COUNT(*) FROM pending").fetchone()[0] >= 1000:
                raise RuntimeError("Pending queue is full; review status before continuing")
            operation_id = str(uuid.uuid4())
            payload = {**values, "kind": kind, "operationId": operation_id, "createdAt": int(time.time()*1000)}
            with self.db:
                self.db.execute("INSERT INTO pending VALUES(?,?,?,NULL)", (signature, operation_id, packed(payload)))
            existing = self.db.execute("SELECT * FROM pending WHERE signature=?", (signature,)).fetchone()
        return dict(existing)

    def send(self, operation):
        if operation["result"]:
            return json.loads(operation["result"])
        result = self.api.request("POST", "/sync/client/operations", json.loads(operation["payload"]))
        with self.db:
            self.db.execute("UPDATE pending SET result=? WHERE signature=?", (packed(result), operation["signature"]))
        return result

    def finish(self, operation):
        with self.db:
            self.db.execute("DELETE FROM pending WHERE signature=?", (operation["signature"],))

    def detect_moves(self, local):
        remote, bases = self.remote(), self.table("baseline")
        paths = self.paths(remote)
        # Only unambiguous OS identities are used. Hash equality alone never claims a rename.
        identities = {}
        for path, value in local.items():
            identities.setdefault(tuple(value["inode"]), []).append(path)
        by_path = {value: eid for eid, value in paths.items()}
        by_path[""] = self.root_id
        for eid, base in sorted(bases.items(), key=lambda item: item[1]["path"].count("/")):
            if eid not in remote or remote[eid]["changeToken"] != base["changeToken"] or base["path"] in local:
                continue
            candidates = identities.get(tuple(base["inode"]), [])
            if len(candidates) != 1 or not base["inode"][1]:
                continue
            new = candidates[0]
            parent = PurePosixPath(new).parent.as_posix()
            parent_id = by_path.get("" if parent == "." else parent)
            if parent_id is None or (new in by_path and by_path[new] != eid):
                continue
            operation = self.prepare("EDIT", {"entryId": eid, "expectedToken": base["changeToken"],
                "name": PurePosixPath(new).name, "parentId": parent_id})
            self.send(operation)
            self.refresh()
            # Do not mark a concurrent local content edit as uploaded.
            with self.db:
                base["path"] = new
                fresh = self.remote().get(eid)
                if fresh and fresh.get("sha256") == base.get("sha256") and self.paths(self.remote()).get(eid) == new:
                    base["changeToken"] = fresh["changeToken"]
                self.db.execute("UPDATE baseline SET payload=? WHERE id=?", (packed(base), eid))
            self.finish(operation)
            return True  # Rescan after each parent move to retain child identity mappings.
        return False

    def install(self, entry, relative):
        target = self.safe(relative)
        before = digest(target) if target.is_file() else None
        stage = self.home / ("download-" + uuid.uuid4().hex)
        try:
            self.api.download(entry, stage)
            self.safe(relative)
            if target.exists() and (not target.is_file() or digest(target) != before):
                self.preserve(relative, "Local content changed while a download was in progress")
            elif target.exists():
                # Retain even a clean replaced version. An editor may still hold its
                # handle after this rename; its last bytes must remain recoverable.
                self.trash(relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            publish_no_replace(stage, target)
        finally:
            if stage.exists():
                stage.unlink()

    def pull(self):
        remote, bases = self.remote(), self.table("baseline")
        paths = self.paths(remote)  # Validate the whole namespace before writing any user path.
        for eid in sorted(remote, key=lambda i: (paths[i].count("/"), remote[i]["kind"] == "FILE", paths[i])):
            entry, relative, base = remote[eid], paths[eid], bases.get(eid)
            path = self.safe(relative)
            if base and base["path"] != relative:
                old = self.safe(base["path"])
                if old.exists() and (not path.exists() or (path_key(base["path"]) == path_key(relative) and old.samefile(path))):
                    if old.is_file() and digest(old) != base.get("sha256"):
                        self.preserve(base["path"], "Cloud moved a locally edited file")
                    else:
                        path.parent.mkdir(parents=True, exist_ok=True)
                        os.rename(old, path)
            if entry["kind"] == "FOLDER":
                if path.exists() and not path.is_dir():
                    self.preserve(relative, "Cloud folder collides with local file")
                if base and not path.exists() and base["changeToken"] == entry["changeToken"]:
                    continue  # Candidate local deletion; children are reconciled before TRASH.
                path.mkdir(parents=True, exist_ok=True)
                self.baseline(entry, relative)
                continue
            if path.is_file():
                sha = digest(path)
                if sha == entry["sha256"]:
                    self.baseline(entry, relative)
                    continue
                if base and base["changeToken"] == entry["changeToken"] and base["path"] == relative:
                    continue  # Local-only edit, eligible for optimistic replacement.
                if not base or sha != base.get("sha256"):
                    self.preserve(relative, "Concurrent cloud and local edits")
            elif path.exists():
                self.preserve(relative, "Cloud file collides with local directory")
            elif base and base["changeToken"] == entry["changeToken"] and base["path"] == relative:
                continue  # Candidate local deletion.
            elif base:
                self.conflict(relative, "Local deletion conflicted with cloud change; cloud copy restored")
            self.install(entry, relative)
            self.baseline(entry, relative)
            if base and base["path"] != relative:
                old = self.safe(base["path"])
                if old.is_file() and digest(old) == base.get("sha256"):
                    self.trash(base["path"])
        for eid, base in sorted(bases.items(), key=lambda item: -item[1]["path"].count("/")):
            if eid in remote:
                continue
            path = self.safe(base["path"])
            if path.exists():
                if path.is_file() and digest(path) != base.get("sha256"):
                    self.preserve(base["path"], "Cloud deleted a locally edited file")
                else:
                    self.trash(base["path"])
            with self.db:
                self.db.execute("DELETE FROM baseline WHERE id=?", (eid,))

    def upload(self, relative, parent_id, sha, entry=None):
        source = self.safe(relative)
        values = {"parentId": parent_id, "name": source.name, "size": source.stat().st_size, "sha256": sha}
        if entry:
            values.update(entryId=entry["id"], expectedToken=entry["changeToken"])
        operation = self.prepare("INIT_UPLOAD", values)
        stage = self.home / ("upload-" + operation["operation_id"])
        self.check(stage)
        if not stage.exists():
            temporary = stage.with_suffix(".tmp")
            self.check(temporary)
            shutil.copyfile(source, temporary)
            if digest(temporary) != sha or temporary.stat().st_size != values["size"]:
                temporary.unlink()
                raise RuntimeError("Local file changed during upload staging; rescan before retry")
            os.replace(temporary, stage)
        if digest(stage) != sha:
            raise RuntimeError("Staged upload checksum is invalid; retain local data and inspect state")
        session = self.send(operation)
        status = self.api.request("GET", f"/sync/client/uploads/{session['id']}")
        if status["status"] != "COMPLETE":
            if status["status"] != "UPLOADING":
                raise RuntimeError("Upload session expired or inactive; review pending queue")
            uploaded = set(status["uploadedParts"])
            with stage.open("rb") as stream:
                for number in range(status["partCount"]):
                    data = stream.read(status["partSize"])
                    if number not in uploaded:
                        with self.api.request("PUT", f"/sync/client/uploads/{session['id']}/parts/{number}", data, binary=True):
                            pass
            status = self.api.request("POST", f"/sync/client/uploads/{session['id']}/complete")
        self.refresh()
        published = self.remote().get(status["fileId"])
        if not published or published["sha256"] != sha:
            raise RuntimeError("Cloud changed immediately after upload; reconcile and preserve local edits")
        self.baseline(published, relative)
        self.finish(operation)
        stage.unlink()

    def push(self):
        local = self.scan()
        remote, bases = self.remote(), self.table("baseline")
        paths = self.paths(remote)
        by_path = {p: remote[eid] for eid, p in paths.items()}
        parents = {p: e["id"] for p, e in by_path.items() if e["kind"] == "FOLDER"}
        parents[""] = self.root_id
        for relative in sorted(local, key=lambda p: (p.count("/"), local[p]["kind"] == "FILE", p)):
            value, entry = local[relative], by_path.get(relative)
            parent = PurePosixPath(relative).parent.as_posix()
            parent_id = parents.get("" if parent == "." else parent)
            if not parent_id:
                raise RuntimeError("Parent folder is not synchronized")
            if entry and entry["kind"] != value["kind"]:
                raise RuntimeError("Entry kind changed during synchronization; rescan")
            if value["kind"] == "FOLDER":
                if not entry:
                    operation = self.prepare("MKDIR", {"parentId": parent_id, "name": PurePosixPath(relative).name})
                    result = self.send(operation)
                    self.refresh()
                    entry = self.remote()[result["id"]]
                    parents[relative] = entry["id"]
                    self.baseline(entry, relative)
                    self.finish(operation)
                continue
            if not entry or value["sha256"] != entry["sha256"]:
                base = bases.get(entry["id"]) if entry else None
                if entry and (not base or base["changeToken"] != entry["changeToken"]):
                    raise RuntimeError("Cloud changed before upload; refresh and resolve conflict")
                self.upload(relative, parent_id, value["sha256"], entry)
        # Children before parents. Server refuses nonempty folder deletion.
        for eid, base in sorted(bases.items(), key=lambda item: -item[1]["path"].count("/")):
            if base["path"] in local or eid not in remote:
                continue
            entry = remote[eid]
            if entry["changeToken"] != base["changeToken"]:
                raise RuntimeError("Cloud changed before local deletion; refresh required")
            operation = self.prepare("TRASH", {"entryId": eid, "expectedToken": base["changeToken"]})
            self.send(operation)
            with self.db:
                self.db.execute("DELETE FROM baseline WHERE id=?", (eid,))
            self.finish(operation)

    def once(self):
        self.scan()  # Fail on local namespace hazards before applying cloud operations.
        self.refresh()
        for _ in range(10000):
            if not self.detect_moves(self.scan()):
                break
        else:
            raise RuntimeError("Move reconciliation limit reached")
        self.pull()
        self.push()
        self.refresh()
        self.setting("lastSuccess", str(int(time.time()*1000)))

    def status(self):
        has_reviewed = "reviewed" in {row[1] for row in self.db.execute("PRAGMA table_info(conflict)")}
        where = "WHERE reviewed=0" if has_reviewed else ""
        return {"trackedEntries": self.db.execute("SELECT COUNT(*) FROM baseline").fetchone()[0],
                "server": self.setting("server"),
                "pendingOperations": self.db.execute("SELECT COUNT(*) FROM pending").fetchone()[0],
                "conflicts": [dict(row) for row in self.db.execute(f"SELECT * FROM conflict {where} ORDER BY created_at DESC LIMIT 100")],
                "lastSuccess": self.setting("lastSuccess")}


def read_status(root):
    """Read WAL state while the background worker owns its exclusive process lock."""
    probe = object.__new__(Engine)
    probe.root = Path(root).absolute()
    home = probe.check(probe.root / ".cloudnest-sync")
    marker = probe.check(home / "format")
    if not marker.is_file() or marker.read_text(encoding="utf-8") != "CloudNest sync state v1\n":
        raise ValueError("This directory has not been linked")
    database = probe.check(home / "state.sqlite3")
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        probe.db = connection
        return probe.status()


def acknowledge(root, records):
    if not records or len(records)>100:
        raise ValueError("Choose 1-100 displayed record IDs")
    for record in records:
        uuid.UUID(record)
    read_status(root)  # Validate marker/path and reject an unlinked or corrupt directory first.
    probe=object.__new__(Engine)
    probe.root=Path(root).absolute()
    database=probe.check(probe.root/".cloudnest-sync"/"state.sqlite3")
    with closing(sqlite3.connect(database.as_uri()+"?mode=rw",uri=True)) as connection:
        with connection:
            placeholders=",".join("?" for _ in records)
            connection.execute(f"UPDATE conflict SET reviewed=1 WHERE id IN ({placeholders})",records)
