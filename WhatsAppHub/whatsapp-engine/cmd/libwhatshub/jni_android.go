//go:build android

package main

/*
#cgo LDFLAGS: -llog
#include <jni.h>
#include <stdlib.h>

static jsize jniByteLen(JNIEnv* env, jbyteArray arr) {
    if (arr == NULL) return 0;
    return (*env)->GetArrayLength(env, arr);
}

static void jniCopyBytesIn(JNIEnv* env, jbyteArray arr, void* dst, jsize len) {
    if (arr == NULL || len <= 0) return;
    (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte*)dst);
}

static jbyteArray jniNewBytesOut(JNIEnv* env, const void* src, jsize len) {
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out != NULL && src != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*)src);
    }
    return out;
}
*/
import "C"

import (
	androidbridge "whatshub/android-bridge"
)

func main() {}

func fromJBytes(env *C.JNIEnv, arr C.jbyteArray) string {
	n := C.jniByteLen(env, arr)
	if n <= 0 {
		return ""
	}
	cBuf := C.malloc(C.size_t(n))
	if cBuf == nil {
		return ""
	}
	defer C.free(cBuf)
	C.jniCopyBytesIn(env, arr, cBuf, n)
	return C.GoStringN((*C.char)(cBuf), C.int(n))
}

func toJBytes(env *C.JNIEnv, s string) C.jbyteArray {
	if len(s) == 0 {
		return C.jniNewBytesOut(env, nil, 0)
	}
	cBytes := C.CBytes([]byte(s))
	defer C.free(cBytes)
	return C.jniNewBytesOut(env, cBytes, C.jsize(len(s)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeVersion
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeVersion(env *C.JNIEnv, _ C.jclass) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().Version())
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeInit
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeInit(
	env *C.JNIEnv,
	_ C.jclass,
	sessionsDir C.jbyteArray,
	mediaDir C.jbyteArray,
	caCertPath C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().Init(
		fromJBytes(env, sessionsDir),
		fromJBytes(env, mediaDir),
		fromJBytes(env, caCertPath),
	)
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeSetSlotKey
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeSetSlotKey(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	keyHex C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().SetSlotKey(int(accountID), fromJBytes(env, keyHex))
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeConnectAccount
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeConnectAccount(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().ConnectAccount(int(accountID)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeDisconnectAccount
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeDisconnectAccount(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().DisconnectAccount(int(accountID)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeRemoveAccount
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeRemoveAccount(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().RemoveAccount(int(accountID)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeRequestPairingCode
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeRequestPairingCode(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	phone C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().RequestPairingCode(int(accountID), fromJBytes(env, phone))
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetAccountsSnapshot
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetAccountsSnapshot(
	env *C.JNIEnv,
	_ C.jclass,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().GetAccountsSnapshot())
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativePeekEvents
func Java_com_rafat_whatshub_bridge_NativeBridge_nativePeekEvents(
	env *C.JNIEnv,
	_ C.jclass,
	maxBatch C.jint,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().PeekEvents(int(maxBatch)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeAckEvents
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeAckEvents(
	env *C.JNIEnv,
	_ C.jclass,
	upToSeq C.jlong,
) C.jbyteArray {
	if upToSeq <= 0 {
		return toJBytes(env, `{"ok":true,"acked_count":0}`)
	}
	return toJBytes(env, androidbridge.Default().AckEvents(uint64(upToSeq)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativePollEvents
func Java_com_rafat_whatshub_bridge_NativeBridge_nativePollEvents(
	env *C.JNIEnv,
	_ C.jclass,
	maxBatch C.jint,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().PollEvents(int(maxBatch)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendText
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendText(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	payloadJSON C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().SendText(int(accountID), fromJBytes(env, payloadJSON))
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendMedia
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendMedia(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	payloadJSON C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().SendMedia(int(accountID), fromJBytes(env, payloadJSON))
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendReaction
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendReaction(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	chatJID C.jbyteArray,
	senderJID C.jbyteArray,
	targetMessageID C.jbyteArray,
	emoji C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().SendReaction(
		int(accountID),
		fromJBytes(env, chatJID),
		fromJBytes(env, senderJID),
		fromJBytes(env, targetMessageID),
		fromJBytes(env, emoji),
	)
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeRevokeMessage
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeRevokeMessage(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	chatJID C.jbyteArray,
	senderJID C.jbyteArray,
	targetMessageID C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().RevokeMessage(
		int(accountID),
		fromJBytes(env, chatJID),
		fromJBytes(env, senderJID),
		fromJBytes(env, targetMessageID),
	)
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeDownloadMedia
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeDownloadMedia(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	payloadJSON C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().DownloadMedia(int(accountID), fromJBytes(env, payloadJSON))
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeMarkRead
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeMarkRead(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	chatJID C.jbyteArray,
	senderJID C.jbyteArray,
	msgIDsCSV C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().MarkRead(
		int(accountID),
		fromJBytes(env, chatJID),
		fromJBytes(env, senderJID),
		fromJBytes(env, msgIDsCSV),
	)
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendTyping
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeSendTyping(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	chatJID C.jbyteArray,
	isTyping C.jboolean,
	isAudio C.jboolean,
) C.jbyteArray {
	res := androidbridge.Default().SendTyping(
		int(accountID),
		fromJBytes(env, chatJID),
		isTyping != C.JNI_FALSE,
		isAudio != C.JNI_FALSE,
	)
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetContacts
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetContacts(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().GetContacts(int(accountID)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetGroups
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetGroups(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().GetGroups(int(accountID)))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetGroupInfo
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeGetGroupInfo(
	env *C.JNIEnv,
	_ C.jclass,
	accountID C.jint,
	groupJID C.jbyteArray,
) C.jbyteArray {
	res := androidbridge.Default().GetGroupInfo(int(accountID), fromJBytes(env, groupJID))
	return toJBytes(env, res)
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeOnNetworkChanged
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeOnNetworkChanged(
	env *C.JNIEnv,
	_ C.jclass,
	available C.jboolean,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().OnNetworkChanged(available != C.JNI_FALSE))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeSetDebugLogging
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeSetDebugLogging(
	env *C.JNIEnv,
	_ C.jclass,
	enabled C.jboolean,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().SetDebugLogging(enabled != C.JNI_FALSE))
}

//export Java_com_rafat_whatshub_bridge_NativeBridge_nativeShutdown
func Java_com_rafat_whatshub_bridge_NativeBridge_nativeShutdown(
	env *C.JNIEnv,
	_ C.jclass,
) C.jbyteArray {
	return toJBytes(env, androidbridge.Default().Shutdown())
}
