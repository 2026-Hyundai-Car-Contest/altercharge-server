#!/usr/bin/env python3
"""Read application dotenv files without executing shell code or exposing secret values."""

import argparse
import ipaddress
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import tempfile
from urllib.parse import urlsplit


class ConfigurationError(Exception):
    pass


KEY = re.compile(r"\s*(?:export\s+)?([A-Z_][A-Z0-9_]*)\s*=(.*)")
PUBLIC_KEYS = {"APP_DOMAIN", "APP_PORT", "HTTP_PORT", "HTTPS_PORT", "COMPOSE_PROJECT_NAME"}
PASSWORD_KEYS = {"POSTGRES_PASSWORD", "POSTGRES_ADMIN_PASSWORD"}


def placeholder(value):
    value = value.strip()
    return not value or value.lower() == "needed" or value.upper().startswith(("YOUR_", "REPLACE_"))


def parse_value(raw, key):
    raw = raw.lstrip()
    if not raw or raw[0] not in "\"'":
        value = re.split(r"\s+#", raw, maxsplit=1)[0].rstrip()
        quoted_literal = False
    else:
        quote = raw[0]
        output = []
        index = 1
        while index < len(raw):
            char = raw[index]
            if char == quote:
                remainder = raw[index + 1:].strip()
                if remainder and not remainder.startswith("#"):
                    raise ConfigurationError(f"{key}: 따옴표 뒤에 잘못된 문자가 있습니다.")
                break
            if char == "\\" and index + 1 < len(raw):
                following = raw[index + 1]
                replacements = {"n": "\n", "r": "\r", "t": "\t", "\\": "\\", '"': '"'}
                if following == quote or quote == '"' and following in replacements:
                    output.append(replacements.get(following, following))
                    index += 2
                    continue
            output.append(char)
            index += 1
        else:
            raise ConfigurationError(f"{key}: 한 줄 안에서 따옴표를 닫아야 합니다.")
        value = "".join(output)
        quoted_literal = quote == "'"
    if any(character in value for character in "\x00\n\r"):
        raise ConfigurationError(f"{key}: 여러 줄 값이나 제어 문자는 지원하지 않습니다.")
    # Compose interpolates unquoted/double-quoted dollar references. Literal secrets must be
    # single-quoted, so validation and Compose cannot resolve them from different environments.
    if not quoted_literal and "$" in value:
        raise ConfigurationError(f"{key}: 달러 문자를 포함한 값은 작은따옴표로 감싸야 합니다.")
    return value


def read_env(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ConfigurationError("일반 파일인 .env가 필요합니다.")
    values = {}
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    positions = {}
    for index, line in enumerate(lines):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = KEY.fullmatch(line)
        if not match:
            raise ConfigurationError(f"환경변수 파일 {index + 1}번째 줄 형식이 올바르지 않습니다.")
        key = match.group(1)
        if key in values:
            raise ConfigurationError(f"{key}: 중복 정의를 제거해야 합니다.")
        values[key] = parse_value(match.group(2), key)
        positions[key] = index
    return values, lines, positions


def write_atomic(path, lines):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".env.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            os.chmod(temporary, 0o600)
            output.write("\n".join(lines) + "\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def initialize(args):
    expected, example_lines, example_positions = read_env(args.example)
    path = Path(args.file)
    if path.is_symlink():
        raise ConfigurationError(".env 심볼릭 링크를 덮어쓰지 않습니다.")
    if path.exists():
        values, lines, positions = read_env(path)
        existing_keys = set(values)
        for key in expected:
            if key not in values:
                positions[key] = len(lines)
                lines.append(example_lines[example_positions[key]])
                values[key] = expected[key]
    else:
        values, lines, positions = expected.copy(), example_lines.copy(), example_positions.copy()
        existing_keys = set()
    for key in PASSWORD_KEYS:
        if key in values and placeholder(values[key]) and not args.preserve_password_placeholders:
            values[key] = secrets.token_urlsafe(48)
            lines[positions[key]] = key + "='" + values[key] + "'"
    if args.mode == "local" and "APP_DOMAIN" in values and (
            "APP_DOMAIN" not in existing_keys or placeholder(values["APP_DOMAIN"])):
        lines[positions["APP_DOMAIN"]] = "APP_DOMAIN=localhost"
    if args.mode == "local" and "HTTPS_PORT" in values and "HTTPS_PORT" not in existing_keys:
        lines[positions["HTTPS_PORT"]] = "HTTPS_PORT=8443"
    write_atomic(path, lines)
    print("환경설정 파일을 준비했습니다. 기존 비밀값은 유지했습니다.")


def integer(values, key, minimum, maximum=9223372036854775807):
    value = values.get(key, "")
    if not re.fullmatch(r"[0-9]+", value) or not minimum <= int(value) <= maximum:
        raise ConfigurationError(f"{key}: 허용된 정수 범위를 확인해야 합니다.")
    return int(value)


def validate(args):
    values, _, _ = read_env(args.file)
    required = ["COMPOSE_PROJECT_NAME", "SPRING_PROFILES_ACTIVE", "APP_DOMAIN", "APP_PORT", "HTTP_PORT",
                "HTTPS_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_ADMIN_PASSWORD",
                "POSTGRES_PORT", "DATA_GO_KR_BASE_URL", "DATA_GO_KR_SERVICE_KEY", "DATA_GO_KR_DATA_TYPE",
                "DATA_GO_KR_ZCODES", "DATA_GO_KR_STATUS_REFRESH_SECONDS", "DATA_GO_KR_INFO_REFRESH_SECONDS",
                "SPRING_JPA_HIBERNATE_DDL_AUTO", "DATA_GO_KR_ENABLED", "DATA_GO_KR_NUM_OF_ROWS",
                "DATA_GO_KR_DAILY_BUDGET", "DATA_GO_KR_METADATA_BUDGET", "FLYWAY_ADOPT_LEGACY_SCHEMA",
                "STATION_STATUS_MAX_AGE_MINUTES", "HISTORY_RETENTION_DAYS", "HISTORY_MAX_FUTURE_MINUTES"]
    missing = [key for key in required if placeholder(values.get(key, ""))]
    if missing:
        raise ConfigurationError("필수 값을 입력해야 합니다: " + ", ".join(missing))
    if args.mode == "production" and stat.S_IMODE(Path(args.file).stat().st_mode) & 0o077:
        raise ConfigurationError("운영 .env 권한을 chmod 600으로 제한해야 합니다.")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", values["COMPOSE_PROJECT_NAME"]):
        raise ConfigurationError("COMPOSE_PROJECT_NAME 형식이 올바르지 않습니다.")
    if args.mode == "production" and values["COMPOSE_PROJECT_NAME"] != "altercharge":
        raise ConfigurationError("운영 볼륨 보존을 위해 COMPOSE_PROJECT_NAME=altercharge를 유지해야 합니다.")
    if values["SPRING_PROFILES_ACTIVE"] != "prod" or values["SPRING_JPA_HIBERNATE_DDL_AUTO"] != "validate":
        raise ConfigurationError("prod 프로필과 Hibernate validate 설정을 유지해야 합니다.")
    for key in ("POSTGRES_DB", "POSTGRES_USER"):
        if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]{0,62}", values[key]):
            raise ConfigurationError(f"{key}: PostgreSQL 식별자 형식이 올바르지 않습니다.")
    if values["POSTGRES_USER"] == "postgres":
        raise ConfigurationError("POSTGRES_USER에는 postgres 관리자 계정과 다른 앱 계정을 사용해야 합니다.")
    if values["POSTGRES_PASSWORD"] == values["POSTGRES_ADMIN_PASSWORD"]:
        raise ConfigurationError("앱과 관리자 DB 비밀번호를 서로 다르게 설정해야 합니다.")
    for key in ("APP_PORT", "HTTP_PORT", "HTTPS_PORT"):
        integer(values, key, 1, 65535)
    if values["POSTGRES_PORT"] != "5432":
        raise ConfigurationError("POSTGRES_PORT는 내부 기본 포트 5432를 유지해야 합니다.")
    if args.mode == "production" and (values["HTTP_PORT"] != "80" or values["HTTPS_PORT"] != "443"):
        raise ConfigurationError("운영 외부 포트는 HTTP 80과 HTTPS 443이어야 합니다.")
    domain = values["APP_DOMAIN"]
    labels = domain.split(".")
    if len(domain) > 253 or any(not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in labels):
        raise ConfigurationError("APP_DOMAIN에는 프로토콜이나 포트가 없는 호스트 이름을 입력해야 합니다.")
    if args.mode == "production":
        try:
            ipaddress.ip_address(domain)
            is_ip = True
        except ValueError:
            is_ip = False
        if is_ip or len(labels) < 2 or domain.lower().endswith((".localhost", ".local", ".invalid", ".test")):
            raise ConfigurationError("APP_DOMAIN에는 공인 HTTPS 인증서를 발급받을 실제 도메인이 필요합니다.")
    url = urlsplit(values["DATA_GO_KR_BASE_URL"])
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment \
            or any(character.isspace() for character in values["DATA_GO_KR_BASE_URL"]):
        raise ConfigurationError("DATA_GO_KR_BASE_URL에는 사용자 정보나 쿼리가 없는 HTTPS 기본 주소가 필요합니다.")
    if url.port is not None and not 1 <= url.port <= 65535:
        raise ConfigurationError("DATA_GO_KR_BASE_URL의 포트 범위가 올바르지 않습니다.")
    if values["DATA_GO_KR_DATA_TYPE"].upper() != "JSON":
        raise ConfigurationError("DATA_GO_KR_DATA_TYPE은 JSON이어야 합니다.")
    if not re.fullmatch(r"[0-9]{2}(?:\s*,\s*[0-9]{2})*", values["DATA_GO_KR_ZCODES"]):
        raise ConfigurationError("DATA_GO_KR_ZCODES에는 두 자리 지역 코드를 입력해야 합니다.")
    integer(values, "DATA_GO_KR_STATUS_REFRESH_SECONDS", 300)
    integer(values, "DATA_GO_KR_INFO_REFRESH_SECONDS", 86400)
    optional_ranges = {"DATA_GO_KR_NUM_OF_ROWS": (10, 9999), "DATA_GO_KR_DAILY_BUDGET": (1, 900),
                       "DATA_GO_KR_METADATA_BUDGET": (1, 900), "STATION_STATUS_MAX_AGE_MINUTES": (1, 1440),
                       "HISTORY_RETENTION_DAYS": (1, 56), "HISTORY_MAX_FUTURE_MINUTES": (0, 60)}
    for key, bounds in optional_ranges.items():
        if key in values:
            integer(values, key, *bounds)
    if "DATA_GO_KR_METADATA_BUDGET" in values and "DATA_GO_KR_DAILY_BUDGET" in values:
        if int(values["DATA_GO_KR_METADATA_BUDGET"]) > int(values["DATA_GO_KR_DAILY_BUDGET"]):
            raise ConfigurationError("메타데이터 호출 예산은 전체 호출 예산 이하여야 합니다.")
    for key in ("DATA_GO_KR_ENABLED", "FLYWAY_ADOPT_LEGACY_SCHEMA"):
        if key in values and values[key].lower() not in ("true", "false"):
            raise ConfigurationError(f"{key}는 true 또는 false여야 합니다.")
    print("환경변수 검증을 통과했습니다.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init")
    init.add_argument("--file", required=True)
    init.add_argument("--example", required=True)
    init.add_argument("--mode", choices=("local", "production"), required=True)
    init.add_argument("--preserve-password-placeholders", action="store_true")
    validation = commands.add_parser("validate")
    validation.add_argument("--file", required=True)
    validation.add_argument("--mode", choices=("local", "production"), required=True)
    get = commands.add_parser("get")
    get.add_argument("--file", required=True)
    get.add_argument("key", choices=sorted(PUBLIC_KEYS))
    args = parser.parse_args()
    try:
        if args.command == "init":
            initialize(args)
        elif args.command == "validate":
            validate(args)
        else:
            values, _, _ = read_env(args.file)
            if args.key not in values:
                raise ConfigurationError("요청한 공개 설정이 없습니다.")
            print(values[args.key])
    except ConfigurationError as error:
        print("환경설정 오류: " + str(error), file=sys.stderr)
        return 1
    except (OSError, UnicodeError, ValueError) as error:
        print("환경설정 파일 처리 실패 (" + type(error).__name__ + ")", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
