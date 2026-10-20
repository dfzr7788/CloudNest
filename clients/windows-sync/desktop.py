"""Small Windows development UI; all synchronization uses the same durable CLI engine."""
from datetime import datetime
import os
from pathlib import Path
import queue
import threading
import urllib.error
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from cloudnest_sync import target
from credentials import WindowsCredentials
from sync_engine import Engine, read_status, acknowledge
from transport import Api, SyncError, normalize_server


TEXT = {
    "title": ("CloudNest 同步 · 开发预览", "CloudNest Sync · Development preview"),
    "notice": ("仅对有独立备份的测试目录启用。冲突不会静默覆盖；不支持链接/网络目录。", "Use backed-up test directories. Conflicts retain copies. Links and network directories are unsupported."),
    "folder": ("本地目录", "Local directory"), "choose": ("选择目录", "Choose folder"),
    "server": ("服务器 HTTPS 地址", "Server HTTPS origin"),
    "token": ("设备凭证（仅绑定时使用）", "Device token (linking only)"),
    "scope": ("选择性同步：已有云端顶层目录名，每行一个；留空同步全部。首次绑定后固定。", "Selective transfer: existing top-level cloud folder names, one per line; blank means all. Fixed after first link."),
    "link": ("绑定设备", "Link device"), "once": ("同步一次", "Sync once"),
    "watch": ("开始后台同步", "Start background sync"), "stop": ("停止", "Stop"),
    "status": ("状态与冲突记录", "Status and conflict records"), "open": ("打开本地目录", "Open local folder"),
    "ack": ("标记显示记录已处理", "Mark displayed records reviewed"),
    "ackConfirm": ("仅标记当前显示的记录已处理，保留文件。请确认已人工比较和处理这些文件。", "Mark only the displayed records reviewed, retaining all files. Confirm you have compared and handled these files manually."),
    "linked": ("已绑定，凭证存入当前 Windows 用户的凭据管理器。尚未传输文件。", "Linked. Credential stored for the current Windows user. No user files transferred yet."),
    "stopping": ("已请求停止；当前网络步骤结束后退出，队列保留。", "Stop requested. Exit after the current network step; queue retained."),
    "closing": ("后台同步仍在运行。停止后关闭窗口？当前网络步骤结束前窗口会保留。", "Synchronization is running. Stop and close after the current network step?"),
    "review": ("冲突文件与本地回收目录会保留。请人工比较文件；本窗口不自动合并或永久删除。", "Conflict copies and local trash are retained. Compare files manually; this window does not merge or permanently delete data."),
    "tracked": ("已跟踪条目", "Tracked entries"), "pending": ("待处理操作", "Pending operations"),
    "lastSuccess": ("最近同步成功", "Last successful sync"), "records": ("待人工查看的记录", "Records for manual review"),
}


class Desktop:
    def __init__(self):
        self.root = tk.Tk()
        self.root.geometry("820x720")
        self.root.minsize(600, 560)
        self.language = tk.StringVar(value="简体中文")
        self.folder, self.server, self.token = (tk.StringVar() for _ in range(3))
        self.events = queue.Queue()
        self.stop = threading.Event()
        self.thread = None
        self.close_requested = False
        self.last_status = None
        self.labels, self.actions, self.wrapped = [], [], []
        frame = ttk.Frame(self.root, padding=16)
        frame.pack(fill="both", expand=True)
        frame.columnconfigure(1, weight=1)
        frame.bind("<Configure>",lambda event: [label.configure(wraplength=max(250,event.width-24)) for label in self.wrapped])
        language = ttk.Combobox(frame, textvariable=self.language, values=["简体中文", "English"], state="readonly", width=14)
        language.grid(row=0, column=2, sticky="e")
        language.bind("<<ComboboxSelected>>", lambda _: self.translate())
        self.label(frame, "notice", 1, span=3, wrap=760)
        self.label(frame, "folder", 2)
        ttk.Entry(frame, textvariable=self.folder).grid(row=2, column=1, sticky="ew", pady=8)
        self.choose_button = self.button(frame, "choose", lambda: self.choose(), 2, column=2)
        self.label(frame, "server", 3)
        ttk.Entry(frame, textvariable=self.server).grid(row=3, column=1, columnspan=2, sticky="ew", pady=8)
        self.label(frame, "token", 4)
        ttk.Entry(frame, textvariable=self.token, show="*").grid(row=4, column=1, columnspan=2, sticky="ew", pady=8)
        self.label(frame, "scope", 5, span=3, wrap=760)
        self.scope = tk.Text(frame, height=3)
        self.scope.grid(row=6, column=0, columnspan=3, sticky="ew", pady=8)
        toolbar = ttk.Frame(frame)
        toolbar.grid(row=7, column=0, columnspan=3, sticky="w", pady=8)
        for column, (key, action) in enumerate([
            ("link", lambda: self.start("link")), ("once", lambda: self.start("once")),
            ("watch", lambda: self.start("watch")), ("stop", self.request_stop),
            ("status", self.status), ("open", self.open_folder),
            ("ack", self.acknowledge),
        ]):
            widget = ttk.Button(toolbar, command=action)
            widget.grid(row=column//3, column=column%3, padx=3, pady=3, sticky="ew")
            self.labels.append((widget, key))
            if key in ("link", "once", "watch"):
                self.actions.append(widget)
        self.label(frame, "review", 8, span=3, wrap=760)
        self.output = tk.Text(frame, wrap="word", state="disabled")
        self.output.grid(row=9, column=0, columnspan=3, sticky="nsew", pady=10)
        scrollbar=ttk.Scrollbar(frame,orient="vertical",command=self.output.yview)
        scrollbar.grid(row=9,column=3,sticky="ns",pady=10)
        self.output.configure(yscrollcommand=scrollbar.set)
        frame.rowconfigure(9, weight=1)
        self.inputs=[widget for widget in frame.winfo_children()
                     if isinstance(widget,(ttk.Entry,tk.Text)) and widget not in (self.output,language)]
        self.inputs.append(self.choose_button)
        self.translate()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(200, self.poll)

    def text(self, key):
        return TEXT[key][0 if self.language.get() == "简体中文" else 1]

    def label(self, frame, key, row, span=1, wrap=0):
        widget = ttk.Label(frame, wraplength=wrap)
        widget.grid(row=row, column=0, columnspan=span, sticky="w", pady=5)
        self.labels.append((widget, key))
        if wrap:
            self.wrapped.append(widget)

    def button(self, frame, key, command, row, column):
        widget = ttk.Button(frame, command=command)
        widget.grid(row=row, column=column, padx=5)
        self.labels.append((widget, key))
        return widget

    def translate(self):
        self.root.title(self.text("title"))
        for widget, key in self.labels:
            widget.configure(text=self.text(key))
        if self.last_status is not None:
            self.show(self.last_status)

    def show(self, value):
        if isinstance(value, dict):
            self.last_status=value
            if value.get("server"):
                self.server.set(value["server"])
            stamp=value.get("lastSuccess")
            last=datetime.fromtimestamp(int(stamp)/1000).isoformat(sep=" ",timespec="seconds") if stamp else "—"
            text=f"{self.text('tracked')}: {value.get('trackedEntries',0)}\n{self.text('pending')}: {value.get('pendingOperations',0)}\n{self.text('lastSuccess')}: {last}\n\n{self.text('records')}:\n"
            text+="\n\n".join(f"{record['path']}\n{record['reason']}" for record in value.get("conflicts",[]))
        else:
            self.last_status=None
            text=str(value)
        self.output.configure(state="normal")
        self.output.delete("1.0", "end")
        self.output.insert("end", text)
        self.output.configure(state="disabled")

    def choose(self):
        selected = filedialog.askdirectory(parent=self.root)
        if selected:
            self.folder.set(selected)

    def start(self, mode):
        if self.thread and self.thread.is_alive():
            return
        folder, server, token = self.folder.get(), self.server.get(), self.token.get().strip()
        names = [name.strip() for name in self.scope.get("1.0", "end").splitlines() if name.strip()]
        if not folder:
            self.choose()
            return
        self.stop.clear()
        for button in self.actions:
            button.configure(state="disabled")
        for widget in self.inputs:
            widget.configure(state="disabled")
        def work():
            engine = None
            try:
                engine = Engine(folder, None)
                credentials = WindowsCredentials()
                if mode == "link":
                    selected = normalize_server(server)
                    previous = engine.setting("server")
                    if previous and previous != selected:
                        raise ValueError("Use a separate directory for a different server")
                    engine.api = Api(selected, token)
                    engine.refresh()
                    if names or engine.setting("scope") is None:
                        engine.configure_scope(names)
                    credentials.write(target(engine.root, selected), token)
                    engine.setting("server", selected)
                    self.events.put(("linked", None))
                else:
                    selected = normalize_server(engine.setting("server") or "")
                    engine.api = Api(selected, credentials.read(target(engine.root, selected)))
                    backoff = 30
                    while not self.stop.is_set():
                        try:
                            engine.once()
                            self.events.put(("status", engine.status()))
                            backoff = 30
                        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                            if mode != "watch":
                                raise
                            self.events.put(("error", "Network unavailable; durable queue retained."))
                            backoff = min(300, backoff * 2)
                        except SyncError as error:
                            if mode != "watch" or (error.status < 500 and error.status != 429):
                                raise
                            self.events.put(("error", str(error)))
                            backoff = min(300, backoff * 2)
                        if mode != "watch" or self.stop.wait(backoff):
                            break
            except Exception as error:
                self.events.put(("error", str(error)))
            finally:
                if engine:
                    engine.close()
                self.events.put(("done", None))
        self.thread = threading.Thread(target=work, daemon=False)
        self.thread.start()

    def request_stop(self):
        self.stop.set()
        self.show(self.text("stopping"))

    def status(self):
        try:
            self.show(read_status(self.folder.get()))
        except Exception as error:
            self.show(str(error))

    def open_folder(self):
        path = Path(self.folder.get()).absolute()
        if self.folder.get() and path.is_dir():
            os.startfile(path)

    def acknowledge(self):
        records=(self.last_status or {}).get("conflicts",[])
        if records and messagebox.askyesno(self.text("title"),self.text("ackConfirm"),parent=self.root):
            try:
                acknowledge(self.folder.get(),[record["id"] for record in records])
                self.status()
            except Exception as error:
                self.show(str(error))

    def poll(self):
        while not self.events.empty():
            kind, value = self.events.get_nowait()
            if kind == "linked":
                self.token.set("")
                self.show(self.text("linked"))
            elif kind == "done":
                for button in self.actions:
                    button.configure(state="normal")
                for widget in self.inputs:
                    widget.configure(state="normal")
                if self.close_requested:
                    self.root.destroy()
                    return
            else:
                self.show(value)
        self.root.after(200, self.poll)

    def close(self):
        if self.thread and self.thread.is_alive():
            if messagebox.askyesno(self.text("title"), self.text("closing"), parent=self.root):
                self.close_requested = True
                self.request_stop()
        else:
            self.root.destroy()


if __name__ == "__main__":
    Desktop().root.mainloop()
