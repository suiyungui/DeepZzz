#!/usr/bin/env python3
import argparse
import sys
import time
from contextlib import ExitStack

from gpiozero import Device, OutputDevice
from gpiozero.pins.lgpio import LGPIOFactory


DEFAULT_IN1 = 33
DEFAULT_IN2 = 46
DEFAULT_PULSE_MS = 250
DEFAULT_SETTLE_MS = 50


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description='IR-CUT H-bridge controller for SpaceMIT K2 / MUSE Pi Pro')
    parser.add_argument('action', choices=['day', 'night', 'off', 'status', 'pulse-a', 'pulse-b'])
    parser.add_argument('--in1', type=int, default=DEFAULT_IN1, help='H-bridge input A GPIO number')
    parser.add_argument('--in2', type=int, default=DEFAULT_IN2, help='H-bridge input B GPIO number')
    parser.add_argument('--pulse-ms', type=int, default=DEFAULT_PULSE_MS, help='drive pulse width in milliseconds')
    parser.add_argument('--settle-ms', type=int, default=DEFAULT_SETTLE_MS, help='delay after releasing both lines')
    parser.add_argument('--active-high', action='store_true', default=True, help='drive H bridge with active-high outputs')
    parser.add_argument('--active-low', dest='active_high', action='store_false', help='invert output polarity if your driver needs it')
    return parser


def set_both(dev1: OutputDevice, dev2: OutputDevice, a: bool, b: bool) -> None:
    dev1.value = 1 if a else 0
    dev2.value = 1 if b else 0


def pulse(dev1: OutputDevice, dev2: OutputDevice, a: bool, b: bool, pulse_ms: int, settle_ms: int) -> None:
    set_both(dev1, dev2, False, False)
    time.sleep(max(settle_ms, 0) / 1000.0)
    set_both(dev1, dev2, a, b)
    time.sleep(max(pulse_ms, 0) / 1000.0)
    set_both(dev1, dev2, False, False)
    time.sleep(max(settle_ms, 0) / 1000.0)


def main() -> int:
    args = build_parser().parse_args()
    Device.pin_factory = LGPIOFactory()

    with ExitStack() as stack:
        in1 = stack.enter_context(OutputDevice(args.in1, active_high=args.active_high, initial_value=False))
        in2 = stack.enter_context(OutputDevice(args.in2, active_high=args.active_high, initial_value=False))

        if args.action == 'status':
            print(f'IN1(GPIO{args.in1})={int(in1.value)} IN2(GPIO{args.in2})={int(in2.value)}')
            return 0

        if args.action == 'off':
            set_both(in1, in2, False, False)
            print(f'IR-CUT outputs released: IN1=0 IN2=0 on GPIO{args.in1}/GPIO{args.in2}')
            return 0

        if args.action in ('day', 'pulse-a'):
            pulse(in1, in2, True, False, args.pulse_ms, args.settle_ms)
            print(f'IR-CUT switched to DAY using GPIO{args.in1}=1 pulse, GPIO{args.in2}=0')
            return 0

        if args.action in ('night', 'pulse-b'):
            pulse(in1, in2, False, True, args.pulse_ms, args.settle_ms)
            print(f'IR-CUT switched to NIGHT using GPIO{args.in1}=0, GPIO{args.in2}=1 pulse')
            return 0

    return 1


if __name__ == '__main__':
    raise SystemExit(main())
