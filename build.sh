#!/bin/sh
set -e

PKG_NAME="$(cd "$(dirname "$0")"; basename "$PWD")"
SRC_URL='https://github.com/AndreyPavlenko/git-st.git'
REV='master'
DEPENDS='git'

: ${PPA:='tools'}
: ${PPA_URL:='http://ppa.launchpad.net/aap'}
: ${DIR:="$(cd "$(dirname "$0")" && pwd)"}
: ${SRC_DIR:="$DIR"}

[ ! -f "$HOME/.build.config" ] || . "$HOME/.build.config" && IGNORE_GLOBAL_CONFIG='true'
[ ! -f "$DIR/build.config" ]   || . "$DIR/build.config" && IGNORE_CONFIG='true'

: ${PPA_BUILDER:="$DIR/ppa-builder"}
: ${PPA_BUILDER_URL:='https://github.com/AndreyPavlenko/ppa-builder.git'}

[ -d "$PPA_BUILDER" ] || git clone "$PPA_BUILDER_URL" "$PPA_BUILDER"
. "$PPA_BUILDER/build.sh"

: ${GIT_SRC_DIR:="$SOURCES_DIR/git"}
: ${GIT_SRC_URL:="https://github.com/git/git.git"}
: ${GIT_REV:="origin/master"}

update() {
    _git_update "$GIT_SRC_URL" "$GIT_SRC_DIR" ${GIT_REV#*/} ${GIT_REV%%/*}
}

_changelog() {
    local cur_version="$(_cur_version "$1")"
    _git_changelog "${cur_version##*~}" "$REV"
}

_checkout() {
    local dest="$1"
    mkdir -p "$dest"
    git --git-dir=$DIR/.git ls-tree --name-only $REV | \
    while read i; do cp -r "$DIR/$i" "$dest"; done
    _git_checkout "$dest/git" "$GIT_REV" "$GIT_SRC_DIR"
}

version() {
    local version="$(git --git-dir="$SRC_DIR/.git" show $REV:build.xml | awk -F '[= \"]+' '/name="project.version" value=/ {print $6}')"
    local ci_count="$(git --git-dir="$SRC_DIR/.git" log --format='%H' $REV | wc -l)"
    local sha="$(git --git-dir="$SRC_DIR/.git" log --format='%h' -n1 $REV)"
    echo "${version}-${ci_count}~${sha}"
}

_main $@

