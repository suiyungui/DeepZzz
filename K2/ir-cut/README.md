# ir-cut

K2 / MUSE Pi Pro 板上的 IR-CUT 控制目录，适用于通过 H 桥驱动 IR-CUT 线圈。

默认引脚：

- 物理 37 脚 -> GPIO33 -> H 桥 IN1
- 物理 38 脚 -> GPIO46 -> H 桥 IN2
- 物理 39 脚 -> GND

选择这两个脚的原因：

- 都在 40Pin 头上，接线方便。
- 官方 `pinout` 识别为普通 GPIO。
- 当前系统 `gpioinfo` 显示它们未被内核或其他驱动占用。
- 这两个脚在连接器底部区域，37/38 为同一排相邻脚位，39 脚就是地线，适合接 H 桥控制输入。

控制逻辑：

- `day`: IN1 输出一个高脉冲，IN2 保持低，然后两路都释放。
- `night`: IN2 输出一个高脉冲，IN1 保持低，然后两路都释放。
- `off`: 两路都拉低。

常用命令：

```bash
cd /home/z/ir-cut
./set_day.sh
./set_night.sh
./release_ir_cut.sh
python3 ir_cut_control.py status
```

自定义脉冲宽度：

```bash
./set_day.sh --pulse-ms 300
./set_night.sh --pulse-ms 300
```

如果你的 H 桥输入是低电平有效：

```bash
./set_day.sh --active-low
./set_night.sh --active-low
```

注意：

- 这里的 GPIO 只驱动 H 桥输入，不直接驱动 IR-CUT 线圈。
- `day/night` 默认只是一个短脉冲，不会长时间持续通电，避免线圈过热。
- 如果你的 IR-CUT 机械方向与这里定义相反，把 `day` 和 `night` 的接线或命令对调即可。
