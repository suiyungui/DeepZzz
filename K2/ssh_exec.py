import os
import sys

import paramiko


# Usage:
#   Set SSH_HOST, SSH_USER, and SSH_PASSWORD in the current shell, then pass the
#   Linux command to run as script arguments. If no command is provided, this
#   script runs "pwd".
#
# Example for the current Linux dev board:
#   $env:SSH_HOST='192.168.22.193'; $env:SSH_USER='z'; $env:SSH_PASSWORD='z'
#   python C:\Users\N___o__person_\.codex\ssh_exec.py 'hostname; whoami; pwd'
#
# Notes:
#   This is not an interactive SSH shell. It connects with Paramiko, executes one
#   command, prints stdout/stderr, returns the remote exit code, and disconnects.
def main() -> int:
    host = os.environ["SSH_HOST"]
    user = os.environ["SSH_USER"]
    password = os.environ["SSH_PASSWORD"]
    command = " ".join(sys.argv[1:]) or "pwd"

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(
        hostname=host,
        username=user,
        password=password,
        look_for_keys=False,
        allow_agent=False,
        timeout=10,
    )

    try:
        stdin, stdout, stderr = client.exec_command(command)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        if out:
            print(out, end="")
        if err:
            print(err, end="", file=sys.stderr)
        return code
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())
