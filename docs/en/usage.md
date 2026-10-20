# Using CloudNest

[简体中文](../usage.md) · [English home](../../README.en.md)

Select **English** or **简体中文** on the login page or in the drive toolbar.
The preference is saved in your browser. Switching does not submit the current
form, reload the route or clear file selection. Core views are translated;
secondary dialogs, AI reading panels and provider diagnostics are still being migrated.

Sign in using the account created by your administrator, or follow an invitation.
When enabled, enter your authenticator code or recovery code. Password reset does
not disable two-factor authentication.

## Files and transfers

Use **New folder**, **Upload files** or **Upload folder** in My drive. Folder uploads
preserve directory structure. Choose how to handle an existing name: replace and
keep history, keep both, or skip. Upload tasks can be paused and resumed; after a
browser restart you may need to select the original local file again for verification.

Click a file to preview it or open its action menu to download, rename, move,
favorite, tag or inspect versions. Downloads request fresh authorization. Selecting
multiple files preserves the selection across directories; review it before applying
batch actions. ZIP jobs appear in the download task area.

Trash and historical versions count towards quota. Restore items from Trash;
permanent deletion cannot be undone. Keep an independent backup of important files.

## Shares

Create a share with an expiry time, optional access code and download permission.
Single-file shares pin the content version at creation; folder shares include newly
added descendants. Save the link and access code and send them only to trusted
recipients. Preview-only access cannot prevent a recipient from saving content
they can see. Revoke a share to stop future access through its link.

## Nodes and AI

Administrators use **Node center** for capacity, health, placement policies,
failure domains, migrations and verified replicas. Logical user usage, physical
node usage and reservations are different measures. Protection requires two online,
verified replicas in independent failure domains. Replication does not replace backup.

AI is optional and disabled by default. Existing assistants use your configured
external provider; review what content is sent and the request budget before
confirming a chargeable request. Administrator Agent Runtime, approval-based
Agent migrations and Windows bidirectional sync remain planned 2.1 work.


## Account email language

Email verification and password reset emails follow the language selected in the web interface.
API clients can send `Accept-Language: en-US` or `zh-CN` when requesting an email.
Missing, invalid or unsupported language preferences default to Chinese. The language is captured
before asynchronous delivery. Links still expire after 15 minutes; the recovery response does not
confirm whether an account exists. Other backend errors and notifications are not fully localized yet.
