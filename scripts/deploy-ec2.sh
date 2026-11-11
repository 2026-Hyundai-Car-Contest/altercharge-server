#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
env_helper="$script_dir/lib/env.py"
app_root="${ALTERCHARGE_ROOT:-/opt/altercharge}"
previous_sha=''
candidate_sha=''
activation_started=0
deployment_committed=0
incoming_directory=''
incoming_backup=''
health_file=''

fail() { printf '%s\n' "$1" >&2; exit 1; }

cleanup() {
  if [[ -n $health_file && -f $health_file ]]; then rm -- "$health_file"; fi
  if [[ -n $incoming_directory || -n $incoming_backup ]]; then
    python3 - "$app_root" "$incoming_directory" "$incoming_backup" <<'PY'
from pathlib import Path
import shutil
import sys
root = Path(sys.argv[1])
for name, directory in zip(sys.argv[2:], ('releases', 'backups')):
    if not name:
        continue
    target = Path(name)
    if target.parent == root / directory and target.name.startswith('.incoming-') and target.is_dir() and not target.is_symlink():
        shutil.rmtree(target)
PY
  fi
  return 0
}
trap cleanup EXIT

compose() {
  local release=$1
  shift
  # The operational .env is the only interpolation source. Inherited shell variables cannot
  # replace its passwords, domain, Compose project, or Docker daemon selection.
  env -i PATH="$PATH" HOME="${HOME:-/home/ubuntu}" docker compose \
    --env-file "$app_root/.env" --project-directory "$release" -p altercharge \
    -f "$release/compose.prod.yaml" "$@"
}

switch_current() {
  local sha=$1
  local temporary_link="$app_root/.current-$$"
  ln -s "releases/$sha" "$temporary_link" && mv -Tf -- "$temporary_link" "$app_root/current"
}

public_health() {
  local attempt
  for ((attempt = 1; attempt <= 20; attempt++)); do
    if curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
        --connect-timeout 5 --max-time 10 --output "$health_file" \
        "https://$domain/actuator/health" 2>/dev/null; then
      if python3 - "$health_file" <<'PY'
import json
import sys
try:
    with open(sys.argv[1], encoding='utf-8') as response:
        healthy = json.loads(response.read(65536)).get('status') == 'UP'
except (OSError, ValueError, AttributeError):
    healthy = False
sys.exit(0 if healthy else 1)
PY
      then return 0; fi
    fi
    if ((attempt < 20)); then sleep 3; fi
  done
  return 1
}

deployment_failed() {
  local status=$1
  trap - ERR
  set +e
  if ((activation_started == 1 && deployment_committed == 0)); then
    printf '배포 검증 실패. DB 스키마와 볼륨은 자동 복구하지 않습니다.\n' >&2
    if [[ -n $previous_sha ]]; then
      if switch_current "$previous_sha" \
          && compose "$app_root/releases/$previous_sha" up -d --build --wait --wait-timeout 180 --no-deps app caddy \
          && public_health; then
        printf '이전 정상 앱과 프록시 설정으로 복구했습니다. 이번 배포는 실패입니다.\n' >&2
      else
        printf '이전 앱 복구도 실패했습니다. DB 마이그레이션 호환성과 앱 상태를 직접 확인해야 합니다.\n' >&2
      fi
    else
      printf '첫 배포여서 복구할 이전 정상 앱이 없습니다. PostgreSQL은 그대로 유지합니다.\n' >&2
      if ! compose "$app_root/releases/$candidate_sha" stop app caddy; then
        printf '실패한 앱·프록시의 중지 상태를 직접 확인해야 합니다.\n' >&2
      fi
      if [[ -L $app_root/current ]]; then rm -- "$app_root/current"; fi
    fi
  else
    printf '배포 준비 또는 후처리에 실패했습니다. 운영 .env와 DB 볼륨은 보존했습니다.\n' >&2
  fi
  exit "$status"
}
trap 'deployment_failed "$?"' ERR

for command in python3 docker flock curl realpath; do
  command -v "$command" >/dev/null 2>&1 || fail "필수 도구가 없습니다: $command"
done
[[ -f $env_helper && ! -L $env_helper ]] || fail '배포용 환경설정 도우미가 없습니다.'
[[ $app_root == /* && $app_root != / && $app_root != /opt && $app_root != /home ]] \
  || fail '운영 루트는 전용 절대 경로여야 합니다.'
[[ -d $app_root && ! -L $app_root ]] || fail 'bootstrap-ec2.sh로 운영 디렉터리를 먼저 준비해야 합니다.'
app_root="$(realpath -e -- "$app_root")"
for directory in staging releases backups scripts docker docker/postgres; do
  [[ -d $app_root/$directory && ! -L $app_root/$directory ]] || fail '운영 하위 디렉터리가 없거나 심볼릭 링크입니다.'
done
[[ ! -L $app_root/.deploy.lock ]] || fail '배포 잠금 파일 심볼릭 링크는 사용할 수 없습니다.'
exec 9> "$app_root/.deploy.lock"
flock -n 9 || fail '다른 배포가 진행 중입니다.'

python3 "$env_helper" validate --file "$app_root/.env" --mode production
domain="$(python3 "$env_helper" get --file "$app_root/.env" APP_DOMAIN)"
if (($# > 1)); then fail '사용법: deploy-ec2.sh [40자리 커밋 SHA]'; fi
if (($# == 1)); then
  candidate_sha=$1
else
  [[ -f $app_root/pending.sha && ! -L $app_root/pending.sha ]] || fail '대기 SHA가 없습니다. 처음에는 deploy-ec2.sh <SHA>로 실행하세요.'
  candidate_sha="$(< "$app_root/pending.sha")"
fi
[[ $candidate_sha =~ ^[0-9a-f]{40}$ ]] || fail '배포 SHA는 소문자 16진수 40자리여야 합니다.'
stage="$app_root/staging/$candidate_sha"
release="$app_root/releases/$candidate_sha"
[[ -d $stage && ! -L $stage ]] || fail '해당 SHA의 staging 디렉터리가 없습니다.'

required_files=(app.jar Dockerfile.runtime .dockerignore compose.prod.yaml Caddyfile.prod \
  docker/postgres/10-create-app-user.sh scripts/deploy-ec2.sh scripts/lib/env.py)

copy_bundle() {
  local source=$1 destination=$2
  python3 - "$source" "$destination" "${required_files[@]}" <<'PY'
from pathlib import Path
import shutil
import sys
import zipfile
source = Path(sys.argv[1])
target = None if sys.argv[2] == '--check-only' else Path(sys.argv[2])
files = sys.argv[3:]
try:
    if source.is_symlink() or not source.is_dir() or (source / '.env').exists():
        raise ValueError('invalid bundle directory')
    for name in files:
        item = source / name
        if not item.is_file() or any(parent.is_symlink() for parent in [item, *item.parents] if parent != source.parent):
            raise ValueError('missing or linked bundle file')
        item.resolve().relative_to(source.resolve())
    with zipfile.ZipFile(source / 'app.jar') as jar:
        if 'BOOT-INF/classes/com/server/altercharger_server/AlterchargerServerApplication.class' not in jar.namelist():
            raise ValueError('not an executable application jar')
    if target is not None:
        for name in files:
            destination = target / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, destination)
            destination.chmod(0o644)
except (OSError, ValueError, zipfile.BadZipFile):
    print('배포 묶음이 누락되었거나 안전한 일반 파일/JAR가 아닙니다.', file=sys.stderr)
    sys.exit(1)
PY
}

if [[ -e $app_root/current && ! -L $app_root/current ]]; then fail 'current는 릴리스 심볼릭 링크여야 합니다.'; fi
if [[ -L $app_root/current ]]; then
  current_target="$(realpath -e -- "$app_root/current")"
  current_name="${current_target##*/}"
  [[ ${current_target%/*} == "$app_root/releases" && $current_name =~ ^[0-9a-f]{40}$ ]] \
    || fail 'current가 관리하는 릴리스 경로를 벗어났습니다.'
fi
if [[ -e $app_root/successful.sha || -L $app_root/successful.sha ]]; then
  [[ -f $app_root/successful.sha && ! -L $app_root/successful.sha ]] || fail '정상 배포 SHA 파일 형식이 올바르지 않습니다.'
  previous_sha="$(< "$app_root/successful.sha")"
  [[ $previous_sha =~ ^[0-9a-f]{40}$ && -d $app_root/releases/$previous_sha && ! -L $app_root/releases/$previous_sha ]] \
    || fail '이전 정상 릴리스가 없습니다. 운영 상태를 먼저 확인하세요.'
fi

incoming_directory="$(mktemp -d "$app_root/releases/.incoming-$candidate_sha-XXXXXX")"
copy_bundle "$stage" "$incoming_directory"
if [[ -e $release || -L $release ]]; then
  [[ -d $release && ! -L $release ]] || fail '기존 SHA 릴리스 경로 형식이 올바르지 않습니다.'
  copy_bundle "$release" --check-only
  for relative in "${required_files[@]}"; do
    [[ -f $release/$relative && ! -L $release/$relative ]] && cmp -s -- "$incoming_directory/$relative" "$release/$relative" \
      || fail '동일 SHA의 기존 릴리스와 다른 파일입니다. 새 커밋으로 배포해야 합니다.'
  done
else
  mv -- "$incoming_directory" "$release"
  incoming_directory=''
fi

init_file="$app_root/docker/postgres/10-create-app-user.sh"
[[ ! -L $init_file ]] || fail 'PostgreSQL 초기화 파일 심볼릭 링크는 사용할 수 없습니다.'
if [[ ! -e $init_file ]]; then install -m 0644 "$release/docker/postgres/10-create-app-user.sh" "$init_file"; fi

compose "$release" config --quiet
health_file="$(mktemp "$app_root/.health-XXXXXX")"
if [[ -n $previous_sha && $previous_sha != "$candidate_sha" ]]; then
  incoming_backup="$(mktemp -d "$app_root/backups/.incoming-$previous_sha-XXXXXX")"
  copy_bundle "$app_root/releases/$previous_sha" "$incoming_backup"
  backup="$app_root/backups/$(date -u +%Y%m%dT%H%M%S)-$previous_sha-${incoming_backup##*-}"
  mv -- "$incoming_backup" "$backup"
  incoming_backup=''
fi
# Failed attempts also retain at most two complete backups of previously successful releases.
retained_backup_shas="$(python3 - "$app_root/backups" <<'PY'
from pathlib import Path
import re
import shutil
import sys
pattern = re.compile(r'[0-9]{8}T[0-9]{6}-([0-9a-f]{40})-[A-Za-z0-9]{6}')
backups = [entry for entry in Path(sys.argv[1]).iterdir()
           if pattern.fullmatch(entry.name) and entry.is_dir() and not entry.is_symlink()]
backups.sort(key=lambda entry: entry.stat().st_mtime_ns, reverse=True)
for entry in backups[2:]:
    shutil.rmtree(entry)
for entry in backups[:2]:
    print(pattern.fullmatch(entry.name).group(1))
PY
)"
pending_file="$(mktemp "$app_root/.pending-XXXXXX")"
printf '%s\n' "$candidate_sha" > "$pending_file"
mv -f -- "$pending_file" "$app_root/pending.sha"
activation_started=1
switch_current "$candidate_sha"
compose "$release" up -d --build --wait --wait-timeout 180
public_health

# Remove old release artifacts only after success. DB volumes are never traversed.
python3 - "$app_root" "$candidate_sha" "$retained_backup_shas" <<'PY'
from pathlib import Path
import re
import shutil
import sys
root = Path(sys.argv[1])
keep = {sys.argv[2], *sys.argv[3].splitlines()}
for entry in (root / 'releases').iterdir():
    if re.fullmatch('[0-9a-f]{40}', entry.name) and entry.name not in keep and entry.is_dir() and not entry.is_symlink():
        shutil.rmtree(entry)
PY
success_file="$(mktemp "$app_root/.successful-XXXXXX")"
printf '%s\n' "$candidate_sha" > "$success_file"
mv -f -- "$success_file" "$app_root/successful.sha"
deployment_committed=1
printf '배포와 공인 HTTPS 상태 검증을 완료했습니다. SHA: %s\n' "$candidate_sha"
