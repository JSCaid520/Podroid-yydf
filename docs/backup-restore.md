# Backup &amp; restore

Podroid keeps **everything inside one ext4 disk image** (`storage.img` in app storage):
the containers, their images, the packages you installed and every file you wrote
in the VM. That single fact drives both ways of moving things to another phone.

| | **Plan A — whole VM** | **Plan B — one container** |
|---|---|---|
| Moves | the entire disk image: every engine, every container, every package, every file, all settings inside the VM | one container's filesystem + its config (env, ports, mounts, start command) |
| Use when | setting up a new phone, or keeping a full snapshot before something risky | sharing one service with a friend, or moving a single container between installs |
| Where | Podroid → Settings → **Container backup** → *Move the whole VM* | `podroid-backup export` in the VM terminal, then `podroid-backup import` on the new phone |
| Size | the whole disk (often several GB) | only that container |
| Needs the VM stopped? | **yes** — a running guest keeps writing to the disk | no, exporting a running container is fine |

---

## Plan A — move the whole VM

### Export on the old phone

1. Stop the VM (Home → Stop). The buttons stay disabled while it runs: copying a
   disk that the guest is writing to produces a corrupted image.
2. Settings → **Container backup** → *Move the whole VM (plan A)* → **Export VM disk**.
3. Pick a destination in the system file picker (Downloads, SD card, a USB drive…)
   and confirm. The default name is `podroid-storage-YYYYMMDD-HHmm.img`.
4. Wait for the progress bar to reach 100%. Move the file to the new phone
   (USB, cloud drive, local send — anything that copies bytes faithfully).

### Import on the new phone

1. Install Podroid and open it **once**, but do **not** start the VM yet.
2. Settings → **Container backup** → **Import VM disk** → pick the `.img` file.
3. Confirm the warning: the current disk is replaced.
4. Start the VM. The guest boots into the exported system: same containers, same
   images, same packages, same files.

### Notes

* The picked file must be an ext4 image — the app checks the superblock magic at
  offset `0x438` before touching anything, so an accidental `.zip`/`.tar` is rejected
  instead of bricking the VM.
* Import never destroys the old disk before the new one is fully written: the
  previous image is renamed to `storage.img.prev` and only deleted after the copy
  succeeds. On failure it is restored automatically.
* The image is not shrunk on import. If the destination phone is configured with a
  larger disk size, the guest grows the filesystem on the next boot; a smaller
  setting keeps the existing (larger) image.
* Free space is checked against the declared file size plus a 256 MB margin.

---

## Plan B — move one container

Every archive written by `podroid-backup export` / `save` now gets a **sidecar
metadata file** next to it (`<archive>.tar.meta`). That sidecar is what makes a
container restorable on a different phone, because `docker export` / `podman export`
only contain the filesystem — never the environment variables, published ports,
mounts or start command.

### Export on the old phone

In the Podroid terminal:

```sh
podroid-backup list                          # what is running, per engine
podroid-backup export my-nginx               # filesystem + my-nginx-<engine>-<time>.tar.meta
podroid-backup save docker.io/library/nginx:latest   # image archive (config lives in the image)
podroid-backup all                           # every container of every engine
```

Archives land in `Downloads/Podroid/backups` (when Downloads sharing is enabled in
Settings) or `/var/backups/podroid` otherwise. **Copy both files** — the `.tar` and
the `.tar.meta` — to `Downloads/Podroid/backups` on the new phone.

### Import on the new phone

In the Podroid terminal on the new phone:

```sh
podroid-backup import /mnt/downloads/Podroid/backups/my-nginx-docker-20260101-120000.tar
```

Opening Settings → **Container backup** on the new phone lists the archives;
**tapping one copies exactly that command**, so you never have to type the path.

Options:

| Option | Meaning |
|---|---|
| `--engine docker\|podman` | force an engine (default: taken from the `.meta`, then from the file name, then the first installed engine) |
| `--name <name>` | container name (default: from the `.meta`, else the archive name) |
| `--replace` | remove an existing container with that name first |
| `--no-run` | only create the container, do not start it |

### What gets restored

| Item | Restored | How |
|---|---|---|
| container filesystem (installed files, written data) | ✅ | `docker import` into `podroid-import/<name>:<time>` |
| environment variables | ✅ | `--env` for each captured variable |
| published ports | ✅ | `--publish`, keeping host IP when it was not `0.0.0.0` |
| bind mounts | ✅ | `--volume`; a missing host directory is created empty so the container can start |
| restart policy | ✅ | `--restart` when it is not `no` |
| network | ✅ (first one) | `--network` when it is not `bridge` |
| entrypoint (single word) | ✅ | `--entrypoint` |
| command (argv) | ✅ | passed to `run` after the image |
| **named volumes** | ❌ | their data lives in a volume directory, not in the container filesystem — back them up separately (`tar` the volume dir or use Plan A) |
| multi-word entrypoint | ⚠️ | `--entrypoint` accepts one word; the imported filesystem still carries the binary, so pass the command explicitly |
| image layers / image name | ⚠️ | an `export` archive is a flat filesystem, so the image is recreated as `podroid-import/<name>:<time>`; use `podroid-backup save` if you need the real image |

### Without the `.meta` file

`import` still works, but it can only restore the filesystem: it guesses the archive
kind (a `save` archive contains `manifest.json` at the root), imports it, and prints
the exact `run` command to finish the job by hand. Nothing is started with guessed
settings.

`save` archives (image archives) restore the image only — no container was captured —
so `import` loads the image and prints the `run` line to use.

---

## 中文速查

**A 方案（整机迁移，推荐换手机用）**：先把虚拟机停掉 → 设置 →「容器备份」→「整机迁移（A 方案）」→「导出虚拟机磁盘」→ 选保存位置，得到 `podroid-storage-日期.img`；新手机装好 Podroid 但先不要启动虚拟机 → 同一页「导入虚拟机磁盘」→ 选那个 `.img` → 确认覆盖 → 启动虚拟机。容器、镜像、已装软件、所有文件原样回来。

**B 方案（单容器迁移）**：在虚拟机终端 `podroid-backup export 容器名`，把生成的 `.tar` **和同名 `.tar.meta`** 一起拷到新手机的 `Downloads/Podroid/backups`；在新手机设置 →「容器备份」（B 方案）里点那个归档即复制 `podroid-backup import '…/xxx.tar'`，粘到终端执行即可。`.meta` 里存的是环境变量、端口、目录挂载、重启策略和启动命令——没有它就只能恢复文件系统，端口和启动参数得自己补。

**限制**：命名卷（named volume）的数据不在容器文件系统里，B 方案不带走；要连卷一起搬，请用 A 方案。
