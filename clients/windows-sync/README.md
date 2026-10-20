# CloudNest Windows Sync — development preview

This is an opt-in prototype for isolated, independently backed-up test data. The server must use
the separate `connected-dev` schema profile. It is **not a stable 2.1 release**.

Requirements: Windows, Python 3.11+ with Tcl/Tk, HTTPS CloudNest origin and a device token generated
in **Account and security → Sync devices**. No account password or S3 credential is stored by this app.

## Desktop installation

Unzip the development client archive and run `install.ps1` in PowerShell. It copies the application
to `%LOCALAPPDATA%\CloudNestSync\development` and creates a current-user Start menu shortcut.
It does not require administrator rights or install an automatic startup task.
`install.ps1 -Run` also opens the window. Python is not bundled; this archive is not a signed EXE installer.
Verify the release `SHA256SUMS` before extracting. When present, the installer checks every file against
the package manifest before copying. Upgrades refuse modified installed files; preserve your changes first.
An existing nonempty directory without an installation ownership marker is also refused.

Close the client before upgrading or uninstalling. To revoke its device and remove the stored credential,
use `logout` before uninstalling, or revoke the device from the web interface. Run
`& "$env:LOCALAPPDATA\CloudNestSync\development\uninstall.ps1"` in PowerShell to remove unchanged installed
files and the owned shortcut. Modified files and unknown contents are retained; there is no recursive
directory deletion. Uninstalling leaves synced files, journals, conflict copies and Windows credentials intact.
For disposable developer tests, `-InstallDirectory` and `-ShortcutDirectory` allow explicit temporary paths;
pass the same `-ShortcutDirectory` when uninstalling. Existing previews without an ownership marker should
be preserved and installed into a separate directory.

Choose a local test directory, enter the HTTPS origin and one-time device token, then **Link device**.
Linking validates metadata and stores the credential in Windows Credential Manager without transferring files.
Then choose **Sync once** or **Start background sync**. The window provides Chinese/English labels,
status and conflict records. Marking displayed records reviewed does not delete their files. Closing an active window asks to stop after the current network step.

For selective transfers, enter existing top-level cloud folder names, one per line, before first linking.
The selection follows folder identity through renames. Changing selection after tracking requires a separate
local directory. Moving a selected root below another cloud folder stops sync for review.
Device authorization still covers the owner's sync APIs; selective transfer is not a folder authorization boundary.

## CLI

```powershell
python cloudnest_sync.py link --server https://cloud.example.com --folder "D:\CloudNest-Test"
# Hidden prompt: paste the scoped device token; never put it in command arguments.
python cloudnest_sync.py once --folder "D:\CloudNest-Test"
python cloudnest_sync.py watch --folder "D:\CloudNest-Test" --interval 30
python cloudnest_sync.py status --folder "D:\CloudNest-Test"
python cloudnest_sync.py logout --folder "D:\CloudNest-Test"
# Mark a manually reviewed status record; its files remain:
python cloudnest_sync.py acknowledge --folder "D:\CloudNest-Test" --record "record-UUID-from-status"

# A separate directory with selective transfers:
python cloudnest_sync.py link --server https://cloud.example.com --folder "D:\CloudNest-Docs" --include "Documents" --include "Photos"
```

`status` can read the local WAL database while the background client runs. `logout` revokes the current
device and deletes its stored credential; local files, baselines and conflicts remain.
HTTP is allowed only for explicit loopback fixtures with `--allow-local-http`; redirects are blocked.

## Semantics and recovery

- Cloud metadata is authoritative. The client retains identity, revision, SHA-256 and a metadata change token.
- `.cloudnest-sync` contains a SQLite WAL journal and staged upload copies. Do not delete it to fix conflicts;
  pending operations use the same ID after a lost response or restart. It contains no device token.
- Downloads are staged, checked against snapshot SHA-256 and atomically installed. Concurrent local writes
  are retained as conflict copies. Changed cloud content during download aborts installation.
- Simultaneous edits create visible `*.conflict-*` siblings. Clean cloud deletions and replaced clean versions move to `.cloudnest-trash`;
  unsynchronized local content is retained. Review records and files manually; there is no automatic merge
  or permanent-delete operation. Marking displayed records reviewed never deletes their files. Local trash is not automatically pruned.
- Uploads use the existing part API, checksum validation and conditional replacement. Expired uploads or
  operations stop for review rather than creating another write blindly. Network failures use bounded
  exponential backoff for durable/idempotent operations.
- Cursor loss triggers a new snapshot while retaining local baselines. The feed reconciles final states;
  it is not a complete audit log of every intermediate edit.
- Polling performs a full local scan for reconciliation; it is not a real-time filesystem watcher.

Limits: 10,000 cloud library entries and 10,000 selected local entries, case/Unicode collision detection, no symbolic links, junctions, hard-linked
files, UNC roots, Windows reserved names or paths exceeding the prototype's 240-character limit.
Unsupported namespaces stop before applying changes; rename them in the web app or use a safe selective scope.
The process lock covers one local directory. Two clients should use different device credentials.

## Testing / 中文说明

从仓库根目录先执行 Maven `verify`，再运行 `python scripts/test-sync-client.py`。
测试启动临时本机 HTTP 服务，使用两个独立目录，不连接已有部署。
覆盖上传/下载、改名与目录移动、选择性传输、离线冲突、删除/恢复、游标失效、
上传响应丢失后的重启、设备撤销、Windows 路径冲突、一万本地小文件扫描和自有测试凭证往返。
这些测试不替代真实两台 Windows PC、真实 Garage 双节点、完整一万文件网络传输或 1GB VPS 验收。

`python scripts/test-sync-installer.py` 在显式临时目录验证安装、升级、卸载、清单校验及文件保留；
Windows CI 同样运行原生 PowerShell/快捷方式测试。卸载不撤销设备或删除 Windows 凭据，
需要撤销时请先在客户端退出设备或在网页撤销；同步数据、冲突副本和本地日志仍保留。

桌面程序为开发预览：选择有独立备份的测试目录，绑定后凭证保存在当前 Windows 用户的凭据管理器。
冲突文件和本地回收目录不会自动删除；请人工比较、整理后再同步。代码见同目录 Python 文件。
