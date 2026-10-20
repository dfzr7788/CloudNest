# Troubleshooting

[简体中文](../deployment.md) · [English home](../../README.en.md)

## Startup or image packaging fails

Run `docker compose config --quiet` and `docker compose ps`. Inspect
`docker compose logs --tail=100 server` and the corresponding MySQL/web logs.
Redact credentials and personal file names before sharing logs.
Both source and prebuilt images now use `cloudnest-server/target/cloudnest-server.jar`.
Rebuild after changing sources; do not manually rename an arbitrary JAR.
After Maven verify, `python3 scripts/check-deployment-artifact.py --require-artifact`
checks the executable deployment artifact.

If a release archive for your revision is missing, inspect that revision's CI
status or build locally from source. Do not substitute another revision's images
while keeping a mismatched database or deployment configuration.

## Cannot connect or stay signed in

Check firewall TCP 8088, the public Compose overlay and whether the service listens
on the expected interface. From the server:

```bash
curl --noproxy '*' -I http://127.0.0.1:8088/
curl --noproxy '*' -f http://127.0.0.1:8088/api/auth/csrf
```

Use `SECURE_COOKIES=false` for plain HTTP, true for HTTPS. Check proxy routing and
forwarded headers when the page loads but API calls fail. Do not expose database
ports to solve a browser/API routing problem.

## Uploads, capacity and replicas

Check quota, node health, write permissions, admin budgets and temporary disk
space. A configured remote budget is not a measurement of provider free capacity.
Restore the provider connection and run the node's full test before retrying.
Avoid deleting source objects manually when a migration or replica job fails.
Only a verified job state is evidence that data has reached its destination.

## Language and background tasks

Use the language selector to override the browser's initial language. Some
secondary dialogs and backend messages still display Chinese. Unknown backend
errors retain their original message rather than losing diagnostic details.
If jobs remain queued, check that the single active application has
`WORKER_ENABLED=true` and is not running in maintenance mode.

## Recovery

Recover from a verified consistent backup into an isolated target first. Keep the
original environment intact until downloads, versions, shares and signatures work
in the target. Database migrations cannot be reversed by replacing only the JAR.
See [deployment and rollback](deployment.md#backup-restore-and-rollback).
