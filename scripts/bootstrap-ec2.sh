#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

trap 'printf "EC2 초기 준비에 실패했습니다. 출력된 단계와 권한을 확인하세요.\n" >&2' ERR

if [[ ${EUID} -ne 0 ]]; then
  printf 'sudo bash scripts/bootstrap-ec2.sh로 실행해야 합니다.\n' >&2
  exit 1
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source_root="$(cd -- "$script_dir/.." && pwd -P)"
app_root=/opt/altercharge
deployment_user=ubuntu

if ! id "$deployment_user" >/dev/null 2>&1; then
  printf 'Ubuntu 기본 배포 계정 ubuntu가 필요합니다.\n' >&2
  exit 1
fi

# Read the OS identity as data, without evaluating a shell configuration file.
os_id="$(sed -n 's/^ID=//p' /etc/os-release | tr -d '\"')"
os_version="$(sed -n 's/^VERSION_ID=//p' /etc/os-release | tr -d '\"')"
os_codename="$(sed -n 's/^VERSION_CODENAME=//p' /etc/os-release | tr -d '\"')"
if [[ $os_id != ubuntu || ! $os_version =~ ^(22|24|26)\.04$ || ! $os_codename =~ ^[a-z]+$ ]]; then
  printf 'Ubuntu 22.04/24.04/26.04 LTS에서 실행해야 합니다.\n' >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends ca-certificates curl rsync python3 util-linux

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  # Existing distro-managed Docker installations are not removed or replaced automatically.
  for package in docker.io docker-compose docker-compose-v2 podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
      printf '기존 Docker 관련 패키지가 있어 자동 교체를 중단했습니다. Compose 플러그인을 먼저 준비하세요.\n' >&2
      exit 1
    fi
  done
  install -m 0755 -d /etc/apt/keyrings
  curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
    https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod 0644 /etc/apt/keyrings/docker.asc
  architecture="$(dpkg --print-architecture)"
  cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $os_codename
Components: stable
Architectures: $architecture
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  apt-get update -qq
  apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

systemctl enable --now docker
docker compose version >/dev/null
usermod -aG docker "$deployment_user"

for directory in "$app_root" "$app_root/staging" "$app_root/releases" "$app_root/backups" \
    "$app_root/scripts" "$app_root/scripts/lib" "$app_root/docker" "$app_root/docker/postgres"; do
  if [[ -L $directory ]]; then
    printf '운영 디렉터리 심볼릭 링크는 사용할 수 없습니다.\n' >&2
    exit 1
  fi
  install -d -m 0750 -o "$deployment_user" -g "$deployment_user" "$directory"
done

for relative in .env.example scripts/deploy-ec2.sh scripts/lib/env.py; do
  source_file="$source_root/$relative"
  target_file="$app_root/$relative"
  if [[ ! -f $source_file || -L $source_file || -L $target_file ]]; then
    printf '초기 준비용 일반 파일이 없거나 심볼릭 링크입니다.\n' >&2
    exit 1
  fi
  if [[ $source_file != "$target_file" ]]; then
    install -m 0640 -o "$deployment_user" -g "$deployment_user" "$source_file" "$target_file"
  fi
done
chmod 0750 "$app_root/scripts/deploy-ec2.sh" "$app_root/scripts/lib/env.py"

# The immutable bootstrap bind source avoids recreating PostgreSQL for every app release.
init_file="$app_root/docker/postgres/10-create-app-user.sh"
if [[ -L $init_file ]]; then
  printf 'PostgreSQL 초기화 파일 심볼릭 링크는 사용할 수 없습니다.\n' >&2
  exit 1
fi
if [[ ! -e $init_file ]]; then
  install -m 0644 -o "$deployment_user" -g "$deployment_user" \
    "$source_root/docker/postgres/10-create-app-user.sh" "$init_file"
fi

init_options=()
if docker volume inspect altercharge_postgres_data >/dev/null 2>&1; then
  # A missing password must be recovered from the existing configuration, never rotated here.
  init_options+=(--preserve-password-placeholders)
fi
python3 "$app_root/scripts/lib/env.py" init --file "$app_root/.env" \
  --example "$app_root/.env.example" --mode production "${init_options[@]}"
chown "$deployment_user:$deployment_user" "$app_root/.env"
chmod 0600 "$app_root/.env"

printf 'EC2 초기 준비 완료. Docker 그룹 적용을 위해 ubuntu SSH 세션에 다시 접속하세요.\n'
if ! python3 "$app_root/scripts/lib/env.py" validate --file "$app_root/.env" --mode production; then
  printf '/opt/altercharge/.env의 필요한 운영 도메인·인증키 값을 직접 채운 뒤 배포하세요.\n'
fi
printf '기존 운영 비밀값과 Docker 볼륨은 보존했습니다.\n'
