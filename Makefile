
ANT := ant
INSTALL := install

GIT_DIR := $(CURDIR)/git
BUILD_DIR := $(CURDIR)/build
DESTDIR := /
PREFIX := /usr/local
LIBEXEC_DIR := $(DESTDIR)$(PREFIX)/lib
GITCORE_DIR := $(LIBEXEC_DIR)/git-core
GIT_MAKE_ARGS :=
JAVA_HOME := $(shell javac="$$(which javac)"; [ ! -h "$$javac" ] || javac="$$(readlink -f $$javac)"; dirname "$$(dirname "$$javac")")


all: build-git build-git-st build-native

build-git:
	echo $(JAVA_HOME)
	cd "$(GIT_DIR)" && $(MAKE) $(GIT_MAKE_ARGS) CFLAGS="-g -O2 -Wall -fPIC" libgit.a xdiff/lib.a

build-git-st:
	JAVA_HOME="$(JAVA_HOME)" $(ANT) jar

build-native:
	cd $(CURDIR)/src/native && \
	make JAVA_HOME="$(JAVA_HOME)" GIT_DIR="$(GIT_DIR)" BUILD_DIR="$(BUILD_DIR)"

install:
	$(INSTALL) -d "$(GITCORE_DIR)"
	$(INSTALL) -m 755 "$(CURDIR)"/bin/* "$(GITCORE_DIR)"
	$(INSTALL) -m 644 "$(BUILD_DIR)"/*.jar "$(GITCORE_DIR)"
	$(INSTALL) -m 644 "$(BUILD_DIR)"/*.so "$(GITCORE_DIR)"

clean:
	cd "$(GIT_DIR)" && $(MAKE) clean
	JAVA_HOME="$(JAVA_HOME)" $(ANT) clean