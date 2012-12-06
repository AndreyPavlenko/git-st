#include <jni.h>
#include <stdio.h>
#include "credential.h"

JNIEXPORT void JNICALL Java_com_aap_gitst_Git_getCredentials(
		JNIEnv * env, jclass javaClass, jobject cb, jstring protocol,
		jstring host, jstring username) {
	struct credential c = CREDENTIAL_INIT;
	jclass cbClass;
	jmethodID approve;
	jboolean approved;

	if (cb == NULL) {
		return;
	}
	if (protocol != NULL) {
		c.protocol = (char*) (*env)->GetStringUTFChars(env, protocol, NULL);
	}
	if (host != NULL) {
		c.host = (char*) (*env)->GetStringUTFChars(env, host, NULL);
	}
	if (username != NULL) {
		c.username = (char*) (*env)->GetStringUTFChars(env, username, NULL);
	}

	credential_fill(&c);
	cbClass = (*env)->GetObjectClass(env, cb);
	approve = (*env)->GetMethodID(env, cbClass, "approve", "(Ljava/lang/String;Ljava/lang/String;)Z");
	approved = (*env)->CallBooleanMethod(env, cb, approve,
			(*env)->NewStringUTF(env, c.username), (*env)->NewStringUTF(env, c.password));

	if (approved) {
		credential_approve(&c);
		credential_clear(&c);
	} else {
		credential_reject(&c);
	}
}
