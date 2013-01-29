#!/bin/sh
set -e

cd "$(dirname "$0")"
DIR="$(pwd)"

# Load ~/.build.config script if exists.
if [ -f "$HOME/.build.config" ]; then . "$HOME/.build.config"; fi

# Load build.config script if exists.
if [ -f "$DIR/build.config" ]; then . "$DIR/build.config"; fi

# Package name
: ${PKG_NAME:="git-st"}

# Source directories
: ${SOURCES_DIR:="$DIR/sources"}
: ${GIT_SRC_DIR:="$SOURCES_DIR/git"}

# Source revisions
: ${GIT_REV:='origin/master'}

# Cache directories
: ${CACHE_DIR:="$DIR/cache"}
: ${BASETGZ_DIR:="$CACHE_DIR/base"}
: ${APT_CACHE_DIR:="$CACHE_DIR/apt"}

# Temporary build directory
: ${BUILD_DIR:="$DIR/build"}

# Output directory for built packages
BUILD_DATE=$(date +%d.%m.%y)
: ${DISTRIBS_DIR:="$DIR/distribs"}
: ${DISTRIBS_SRC_DIR:="$DISTRIBS_DIR/$BUILD_DATE/src"}
: ${DISTRIBS_DEB_DIR:="$DISTRIBS_DIR/$BUILD_DATE/deb"}

# Command aliases
: ${SUDO:='sudo'}
: ${RM:='/bin/rm'}

# Package maintainer
: ${MAINTAINER:="Unknown <unknown@unknown>"}

# pbuilder arguments
: ${PBUILDER_ARGS:=--configfile "$DIR/.pbuilderrc" --aptcache "$APT_CACHE_DIR" \
                    --buildplace "$BUILD_DIR" --buildresult "$DISTRIBS_DEB_DIR"}
[ -z "$http_proxy" ] || PBUILDER_ARGS="$PBUILDER_ARGS --http-proxy "$http_proxy""

# dpkg-buildpackage args
: ${BUILDPACKAGE_ARGS:="-uc -us"}

# Space separated list of target platfroms
: ${TARGET_PLATFROMS:="$(lsb_release -cs):$(dpkg-architecture -qDEB_BUILD_ARCH)"}

# Build script dependencies
: ${DEPENDS:="pbuilder debootstrap lsb-release dpkg-dev debhelper git perl"}

# Aptitude tag to mark all installed dependencies
: ${DEPENDS_TAG:="$PKG_NAME-build"}

# PPA url
: ${PPA_URL:="http://ppa.launchpad.net/aap"}

# PPA name for upload
: ${PPA:="tools"}

# PPA version
: ${PPAN:="ppa1"}

# URL of ppa sources
: ${PPA_SOURCES:="$PPA_URL/$PPA/ubuntu/dists/#DISTRIB#/main/source/Sources.gz"}

# Skip targets
: ${SKIP_DEPENDS:="false"}
: ${SKIP_UPDATE:="false"}
: ${SKIP_UPDATE_BASE:="false"}
: ${SKIP_UPLOAD:="true"}

depends() {
    echo "depends:"
    echo
    echo "Installing dependencies: $DEPENDS"
    echo
    echo "Note: you may uninstall the installed dependencies with the following command:"
    echo "sudo aptitude purge '?user-tag($DEPENDS_TAG)'"
    echo
    $SUDO apt-get install aptitude
    $SUDO aptitude install --add-user-tag "$DEPENDS_TAG" $DEPENDS
}

clean_depends() {
    echo "clean_depends:"
    $SUDO aptitude purge "?user-tag($DEPENDS_TAG)"
}

clean_cache() {
    echo "clean_cache:"
    $RM -rf "$CACHE_DIR"
}

clean_sources() {
    echo "clean_sources:"
    $RM -rf "$GIST_SRC_DIR"
    $RM -rf "$GIT_SRC_DIR"
}

clean_distribs() {
    echo "clean_distribs:"
    $RM -rf "$DISTRIBS_DIR"
}

clean_all() {
    echo "clean_all:"
    clean_cache
    clean_depends
    clean_sources
    clean_distribs
}

update() {
    echo "update:"

    if [ ! -d "$GIT_SRC_DIR" ]
    then
        git clone --no-checkout 'https://github.com/git/git.git' "$GIT_SRC_DIR"
    else
        cd "$GIT_SRC_DIR"
        git fetch
    fi
}

create_source_packages() {
    echo "create_source_packages:"
    eval local $(grep 'name="project.version"' "$DIR/build.xml" -m 1 | awk '{print $3}')
    local ci_count=$(git --git-dir="$DIR/.git" log --pretty=format:'' | wc -l)
    local sha_short=$(git --git-dir="$DIR/.git" rev-parse --short HEAD)
    local version="$value"
    local src="$BUILD_DIR/${PKG_NAME}_${version}-${ci_count}~${sha_short}"
    local orig_tar="$BUILD_DIR/$(basename "$src").orig.tar.bz2"
    
    # Checkout git-st
    $RM -rf "$BUILD_DIR"
    mkdir -p "$src"
    git --git-dir="$DIR/.git" --work-tree="$src" reset --hard HEAD
    
    # Checkout git
    mkdir -p "$src/git"
    git --git-dir="$GIT_SRC_DIR/.git" --work-tree="$src/git" reset --hard "$GIT_REV"
    
    # Create orig source tarball
    tar -C "$BUILD_DIR" -cjf "$orig_tar" "$(basename "$src")"
    
    # Generate changelog
    cp -r "$DIR/debian" "$BUILD_DIR"
    git --git-dir="$DIR/.git" log \
        --format="#PKG# (#VER#-#CIn#~%h-#PPAn#~#DIST#) #DIST#; urgency=medium%n%B%n-- %aN <%ae>  %aD%n"\
        | tac | sed -E 's/^([^#-])/  * \1/g; s/^--/ --/'\
        | tac > "$BUILD_DIR/debian/changelog"
    
    
    local c='0'
    local ver_replace="s/#PKG#/$PKG_NAME/g; s/#PPAn#/$PPAN/g"
    for h in $(git --git-dir="$DIR/.git" log --format="%h" | tac)
    do
        eval local $(git --git-dir="$DIR/.git" show ${h}:build.xml | \
                     grep 'name="project.version"' -m 1 | awk '{print $3}')
        ver_replace="$ver_replace; s/#VER#-#CIn#~${h}/${value}-${c}~${h}/"
        c=$(( $c + 1 ))
    done
    
    sed -i "$ver_replace" "$BUILD_DIR/debian/changelog"
    sed -i "s/#MAINTAINER#/$MAINTAINER/" "$BUILD_DIR/debian/control"
    
    # Create source packages
    for dist in $(for i in $TARGET_PLATFROMS; do echo $i | awk -F ':' '{print $1}'; done | sort -u)
    do
        $RM -rf "$src/debian"
        cp -r "$BUILD_DIR/debian" "$src"
        sed -i "s/#DIST#/$dist/g;" "$src/debian/changelog"

        cd "$src"
        dpkg-buildpackage -rfakeroot $BUILDPACKAGE_ARGS -S -sa
        $RM -rf "$src/debian"
    done

    # Move package to $DISTRIBS_SRC_DIR
    [ -d "$DISTRIBS_SRC_DIR" ] || mkdir -p "$DISTRIBS_SRC_DIR"
    cd "$BUILD_DIR"
    PACKAGES=$(ls *.dsc)
    mv *.tar.* *.dsc *.changes "$DISTRIBS_SRC_DIR"
    $RM -rf "$BUILD_DIR"
}

_pbuild_create() {
    local distrib=$1
    local arch=$2
    local btgz="$BASETGZ_DIR/${distrib}_${arch}.tgz"

    if [ ! -f "$btgz" ]
    then
        echo "Creating base tarball: $btgz"
        [ -d "$BUILD_DIR" ] || mkdir -p "$BUILD_DIR"
        [ -d "$BASETGZ_DIR" ] || mkdir -p "$BASETGZ_DIR"
        [ -d "$APT_CACHE_DIR" ] || mkdir -p "$APT_CACHE_DIR"

        $SUDO pbuilder create --debootstrapopts --variant=buildd --basetgz "$btgz"\
              --distribution ${distrib} --architecture ${arch} \
              $PBUILDER_ARGS || ($RM -f "$btgz" && return 1)
    elif [ "$SKIP_UPDATE_BASE" != "true" ]
    then
        echo "Updating base tarball: $btgz"
        [ -d "$BUILD_DIR" ] || mkdir -p "$BUILD_DIR"
        [ -d "$APT_CACHE_DIR" ] || mkdir -p "$APT_CACHE_DIR"

        $SUDO pbuilder update --basetgz "$btgz" $PBUILDER_ARGS
    fi
}

_pbuild_build() {
    local distrib=$1
    local arch=$2
    local btgz="$BASETGZ_DIR/${distrib}_${arch}.tgz"
    local pkgs=$(for i in $PACKAGES; do case $i in *~$distrib.dsc) echo $i;; esac; done)

    echo "Building packages: $pkgs"
    [ -d "$BUILD_DIR" ] || mkdir -p "$BUILD_DIR"
    [ -d "$DISTRIBS_DEB_DIR" ] || mkdir -p "$DISTRIBS_DEB_DIR"
    [ -d "$APT_CACHE_DIR" ] || mkdir -p "$APT_CACHE_DIR"

    $SUDO pbuilder build --basetgz "$btgz" $PBUILDER_ARGS $pkgs
}

build_source_packages() {
    echo "build_source_packages:"
    cd "$DISTRIBS_SRC_DIR"
    : ${PACKAGES:=$(ls *.dsc)}
    $RM -rf "$BUILD_DIR"

    for i in $TARGET_PLATFROMS
    do
        local distrib=$(echo $i | awk -F ':' '{print $1}')
        local arch=$(echo $i | awk -F ':' '{print $2}')

        _pbuild_create $distrib $arch
        _pbuild_build  $distrib $arch
    done

    cd "$DISTRIBS_DEB_DIR"
    $RM -f *.tar.bz2 *.dsc *.changes
    $RM -rf "$BUILD_DIR"
}

upload() {
    echo "upload:"
    cd "$DISTRIBS_SRC_DIR"
    : ${PACKAGES:=$(ls *.dsc)}
    local changes="$(for i in $PACKAGES; do echo $i | sed 's/.dsc$/_source.changes/'; done)"

    echo dput "$PPA" $changes
    dput "$PPA" $changes
}

check_updates() {
	[ "$SKIP_UPDATE" = "true" ] || update
    eval local $(grep 'name="project.version"' "$DIR/build.xml" -m 1 | awk '{print $3}')
    local ci_count=$(git --git-dir="$DIR/.git" log --pretty=format:'' | wc -l)
    local sha_short=$(git --git-dir="$DIR/.git" rev-parse --short HEAD)
    local version="${value}-${ci_count}~${sha_short}"
	local names=''
	
	: ${TARGET_PLATFROMS:="$(lsb_release -cs):$(dpkg-architecture -qDEB_BUILD_ARCH)"}
    for dist in $(for i in $TARGET_PLATFROMS; do echo $i | awk -F ':' '{print $1}'; done | sort -u)
    do
        local url="$(echo "$PPA_SOURCES" | sed "s/#DISTRIB#/$dist/")"
        echo -n "Checing updates for $PKG_NAME: $url ... - "
        
        if curl "$url" 2>/dev/null | gunzip 2>/dev/null | grep "^Package: $PKG_NAME$" -A2 | grep -q "$version-"
        then
        	echo "Up to date."
        else
        	names="$names $dist"
        	echo "Updates are available."
        fi
    done
    
    if [ -z "$names" ]
    then
    	return 0
    else
    	echo "$names" 1>&2
    	return 1
    fi
}

build() {
    echo "build:"
    [ "$SKIP_DEPENDS" = "true" ] || depends
    [ "$SKIP_UPDATE" = "true" ] || update
    create_source_packages
    build_source_packages
    [ "$SKIP_UPLOAD" = "true" ] || upload
}

TARGETS=$(grep -Eo '^ *[a-z]\w+ *\(\)' build.sh | tr -d '[ \t\(\)]' | sort | tr '\n' '|' | head -c -1)
[ "$1" = '' ] && target='build' || target="$@"

for t in $target
do
    if echo "$t" | grep -Eq "^($TARGETS)\$"
    then
        $t
    else
        echo "Unknown target $t"
        echo "Usage: $0 <$TARGETS>"
    fi
done
