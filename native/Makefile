CC := cc
RM := rm -rf
BUILD_DIR := ../build
CCFLAGS := -g -O2 -fPIC
GIT_INCLUDES := -I"$(GIT)"
JNI_INCLUDES := -I"$(JAVA_HOME)/include" -I"$(JAVA_HOME)/include/linux" -I"$(JAVA_HOME)/include/win32"

all:
	$(CC) $(CCFLAGS) -o $(BUILD_DIR)/git-st.o -c $(GIT_INCLUDES) $(JNI_INCLUDES) git-st.c 
	$(CC) $(CCFLAGS) -o $(BUILD_DIR)/libgit-st.so -shared -Wl,-soname,libgit-st.so $(BUILD_DIR)/git-st.o "$(GIT)/libgit.a" "$(GIT)/xdiff/lib.a" -lz -lcrypto -pthread
	
clean:
	$(RM) $(BUILD_DIR)/git-st.o $(BUILD_DIR)/libgit-st.so
