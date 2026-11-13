#!/usr/bin/env python3
"""Start the local Compose stack using validated settings, without rewriting .env."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
from types import SimpleNamespace

from lib.env import ConfigurationError, validate


REPO = Path(__file__).resolve().parent.parent


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=REPO / ".env")
    args = parser.parse_args(argv)
    try:
        env_file = args.env_file.absolute()
        values = validate(SimpleNamespace(file=env_file, mode="local"))
        domain = values["APP_DOMAIN"].lower()
        if domain != "localhost" and not domain.endswith(".localhost"):
            raise ConfigurationError("로컬 APP_DOMAIN은 localhost 또는 .localhost 도메인이어야 합니다.")
        if not shutil.which("docker"):
            raise ConfigurationError("Docker Desktop 또는 Docker Compose를 설치하고 실행해야 합니다.")
        # Compose gives process environment precedence over --env-file. Remove only the
        # file's keys so validation and the launched containers use the same values.
        process_env = {key: value for key, value in os.environ.items() if key not in values}
        compose = ["docker", "compose", "--env-file", str(env_file), "-f", str(REPO / "compose.yaml")]
        subprocess.run(compose + ["config", "--quiet"], cwd=REPO, env=process_env, check=True)
        subprocess.run(compose + ["up", "--detach", "--build", "--wait"],
                       cwd=REPO, env=process_env, check=True)
        print(f"Swagger: https://{domain}:{values['HTTPS_PORT']}/swagger-ui/index.html")
        print("최초 HTTPS 접속 시 로컬 Caddy CA를 신뢰하도록 설정해 주세요. docs/LOCAL_RUN.md 참고")
        return 0
    except ConfigurationError as error:
        print("로컬 실행 설정 오류: " + str(error), file=sys.stderr)
    except subprocess.CalledProcessError as error:
        print("Docker Compose 실행 실패. 위 로그와 Docker 실행 상태를 확인해 주세요.", file=sys.stderr)
        return error.returncode
    except OSError as error:
        print("로컬 실행 실패 (" + type(error).__name__ + ")", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
