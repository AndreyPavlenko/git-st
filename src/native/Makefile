CC := cc
RM := /bin/rm
BUILD_DIR := ../../build
CCFLAGS := -g -O2 -Wall -fPIC
GIT_DIR := ../../git
GIT_INCLUDES := -I"$(GIT_DIR)"
JNI_INCLUDES := -I"$(JAVA_HOME)/include" -I"$(JAVA_HOME)/include/linux" -I"$(JAVA_HOME)/include/win32"

all:
	$(CC) $(CCFLAGS) -o $(BUILD_DIR)/libgit-st.so $(GIT_INCLUDES) $(JNI_INCLUDES) \
	      -shared git-st.c "$(GIT_DIR)/libgit.a" "$(GIT_DIR)/xdiff/lib.a" -lz -lcrypto -pthread
	
clean:
	$(RM) -f $(BUILD_DIR)/libgit-st.so
