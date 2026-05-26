# day_night

K2 / MUSE Pi Pro 板上的高低电平检测目录。

默认引脚：

- 物理 36 脚 -> `GPIO35`

选择 `GPIO35` 的原因：

- 你说明了 `GPIO33` 和 `GPIO46` 已经被占用。
- 从你给的 40Pin 引脚图看，`GPIO35` 在同一排针上，适合直接接一个数字输入。

逻辑：

- 低电平打印 `day`
- 高电平打印 `night`
- 在 `sync-ircut` 模式下，检测到 `day` 时会触发 IR-CUT 到 `day`，检测到 `night` 时会触发 IR-CUT 到 `night`

默认配置：

- 默认使用内部下拉，也就是悬空时更偏向读到低电平
- 如果你的外部模块已经自带上拉/下拉，可以改用 `--floating`
- 如果你的输入逻辑相反，可以加 `--active-low`

常用命令：

```bash
cd /home/z/day_night
chmod +x check_day_night.sh watch_day_night.sh sync_day_night_ircut.sh
./check_day_night.sh
./watch_day_night.sh
./sync_day_night_ircut.sh
```

联动 IR-CUT：

```bash
cd /home/z/day_night
./sync_day_night_ircut.sh
```

这个模式会：

- 启动时先读取当前状态并打印 `day` 或 `night`
- 同时调用 `/home/z/ir-cut/ir_cut_control.py`，把 IR-CUT 切到同样状态
- 后续只有在状态变化时才再次触发 IR-CUT，避免重复打线圈脉冲

开机自启：

```bash
sudo cp day-night-sync.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now day-night-sync.service
```

这个服务会：

- 开机自动运行联动监听
- 由 `systemd` 托管并在异常退出后自动重启
- 把日志写到 `/home/z/day_night/logs/sync_day_night_ircut.log`

指定别的 GPIO：

```bash
./check_day_night.sh --gpio 74
./watch_day_night.sh --gpio 74
./sync_day_night_ircut.sh --gpio 74
```

如果你想启用内部上拉：

```bash
./check_day_night.sh --pull-up
./watch_day_night.sh --pull-up
./sync_day_night_ircut.sh --pull-up
```

如果外部信号本身已经稳定驱动高低电平，不想开内部上下拉：

```bash
./check_day_night.sh --floating
./watch_day_night.sh --floating
./sync_day_night_ircut.sh --floating
```

如果 IR-CUT H 桥是低电平有效：

```bash
./sync_day_night_ircut.sh --ir-active-low
```
