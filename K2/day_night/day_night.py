#!/usr/bin/env python3
import argparse
import subprocess
import sys
import time
from contextlib import ExitStack
from pathlib import Path

from gpiozero import Device, DigitalInputDevice
from gpiozero.pins.lgpio import LGPIOFactory


DEFAULT_GPIO = 35
DEFAULT_INTERVAL_MS = 100
DEFAULT_IRCUT_SCRIPT = Path(__file__).resolve().parent.parent / 'ir-cut' / 'ir_cut_control.py'
DEFAULT_IRCUT_IN1 = 33
DEFAULT_IRCUT_IN2 = 46
DEFAULT_IRCUT_PULSE_MS = 250
DEFAULT_IRCUT_SETTLE_MS = 50


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description='Print "day" on high level and "night" on low level for a K2 GPIO input.'
    )
    parser.add_argument(
        'action',
        choices=['status', 'watch', 'sync-ircut'],
        nargs='?',
        default='status',
        help='status: print current state once; watch: print when the input changes; sync-ircut: also drive IR-CUT',
    )
    parser.add_argument(
        '--gpio',
        type=int,
        default=DEFAULT_GPIO,
        help='GPIO number to read, default is GPIO35 (physical pin 36 on the 40-pin header)',
    )
    parser.add_argument(
        '--interval-ms',
        type=int,
        default=DEFAULT_INTERVAL_MS,
        help='poll interval in milliseconds for watch mode',
    )
    parser.add_argument(
        '--debounce-ms',
        type=int,
        default=20,
        help='debounce time in milliseconds',
    )
    parser.add_argument(
        '--active-low',
        action='store_true',
        help='invert the input logic so low prints day and high prints night',
    )

    pull = parser.add_mutually_exclusive_group()
    pull.add_argument(
        '--pull-up',
        action='store_true',
        help='enable the internal pull-up resistor',
    )
    pull.add_argument(
        '--floating',
        action='store_true',
        help='do not enable an internal pull resistor',
    )
    parser.add_argument(
        '--ir-cut-script',
        default=str(DEFAULT_IRCUT_SCRIPT),
        help='path to ir_cut_control.py',
    )
    parser.add_argument(
        '--in1',
        type=int,
        default=DEFAULT_IRCUT_IN1,
        help='IR-CUT H-bridge input A GPIO number',
    )
    parser.add_argument(
        '--in2',
        type=int,
        default=DEFAULT_IRCUT_IN2,
        help='IR-CUT H-bridge input B GPIO number',
    )
    parser.add_argument(
        '--pulse-ms',
        type=int,
        default=DEFAULT_IRCUT_PULSE_MS,
        help='IR-CUT drive pulse width in milliseconds',
    )
    parser.add_argument(
        '--settle-ms',
        type=int,
        default=DEFAULT_IRCUT_SETTLE_MS,
        help='IR-CUT delay after releasing both lines in milliseconds',
    )
    parser.add_argument(
        '--ir-active-low',
        action='store_true',
        help='drive the IR-CUT H-bridge with active-low outputs',
    )
    return parser


def resolve_pull_mode(args: argparse.Namespace):
    if args.pull_up:
        return True
    if args.floating:
        return None
    return False


def resolve_label(value: int, active_low: bool) -> str:
    logical_high = bool(value) if active_low else not value
    return 'day' if logical_high else 'night'


def sync_ircut(state: str, args: argparse.Namespace) -> None:
    command = [
        sys.executable,
        str(Path(args.ir_cut_script)),
        state,
        '--in1',
        str(args.in1),
        '--in2',
        str(args.in2),
        '--pulse-ms',
        str(args.pulse_ms),
        '--settle-ms',
        str(args.settle_ms),
    ]
    command.append('--active-low' if args.ir_active_low else '--active-high')
    subprocess.run(command, check=True)


def main() -> int:
    args = build_parser().parse_args()
    Device.pin_factory = LGPIOFactory()

    bounce_time = max(args.debounce_ms, 0) / 1000.0 if args.debounce_ms is not None else None
    interval_s = max(args.interval_ms, 1) / 1000.0

    with ExitStack() as stack:
        sensor = stack.enter_context(
            DigitalInputDevice(
                args.gpio,
                pull_up=resolve_pull_mode(args),
                bounce_time=bounce_time,
            )
        )

        if args.action == 'status':
            print(resolve_label(sensor.value, args.active_low))
            return 0

        last_label = None
        while True:
            current_label = resolve_label(sensor.value, args.active_low)
            if current_label != last_label:
                print(current_label, flush=True)
                if args.action == 'sync-ircut':
                    sync_ircut(current_label, args)
                last_label = current_label
            time.sleep(interval_s)


if __name__ == '__main__':
    raise SystemExit(main())
