"""Prepare the exact upstream sources and apply DormPanel's audio-only changes."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
from urllib.request import urlopen

work, cache = map(Path, sys.argv[1:])
lock = json.loads(Path(__file__).with_name("sources.json").read_text(encoding="utf-8-sig"))
src = work / "src"
licenses = work / "licenses"
licenses.mkdir(exist_ok=True)


def extract(entry):
    archive = cache / entry["archive"]
    if not archive.exists():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
            path = Path(temporary.name)
            try:
                with urlopen(entry["url"], timeout=60) as response:
                    shutil.copyfileobj(response, temporary)
                temporary.flush()
                with path.open("rb") as handle:
                    if hashlib.file_digest(handle, "sha256").hexdigest() != entry["sha256"]:
                        raise ValueError(f"SHA-256 mismatch: {entry['name']}")
                path.replace(archive)
            finally:
                path.unlink(missing_ok=True)
    with archive.open("rb") as handle:
        if hashlib.file_digest(handle, "sha256").hexdigest() != entry["sha256"]:
            raise ValueError(f"SHA-256 mismatch: {entry['name']}")
    target = src / entry["name"]
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        for member in tar:
            parts = Path(member.name).parts
            if len(parts) > 1:
                member.name = str(Path(*parts[1:]))
                tar.extract(member, target, filter="data")
    for path in target.iterdir():
        if path.is_file() and path.name.startswith(("COPYING", "LICENSE", "NOTICE", "AUTHORS")):
            shutil.copyfile(path, licenses / f"{entry['name']}-{path.name}")


with concurrent.futures.ThreadPoolExecutor() as pool:
    list(pool.map(extract, lock))

cpp = src / "airplay/app/src/main/cpp"
deps = cpp / "third_party"
deps.mkdir(exist_ok=True)
for name in ("UxPlay", "libplist", "ffmpeg"):
    link = deps / name
    if link.is_dir():
        link.rmdir()
    link.symlink_to(src / name, target_is_directory=True)
for patch in sorted((cpp / "patches/UxPlay").glob("000[678]-*.patch")):
    subprocess.run(["patch", "-p1", "--batch", "-i", str(patch)], cwd=deps / "UxPlay", check=True)


def replace(path, old, new):
    source = path.read_text()
    if source.count(old) != 1:
        raise ValueError(f"Unexpected source in {path}: {old}")
    path.write_text(source.replace(old, new))


cmake = cpp / "CMakeLists.txt"
source = cmake.read_text()
start = source.index("set(BUILD_OPENSSL")
end = source.index("# ---------- Sanitizers")
cmake.write_text(source[:start] + """
string(REGEX MATCH "[0-9]+" AIRPLAY_ANDROID_API "${ANDROID_PLATFORM}")
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
    IMPORTED_LOCATION "${DORMPANEL_OPENSSL}/lib/libcrypto.a"
    INTERFACE_INCLUDE_DIRECTORIES "${DORMPANEL_OPENSSL}/include")
list(PREPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/cmake)

""" + source[end:])
replace(cmake, "find_package(oboe REQUIRED CONFIG)", "add_subdirectory(${DORMPANEL_OBOE} oboe)")
replace(cmake, "oboe::oboe", "oboe")

lib = deps / "UxPlay/lib"
shutil.copyfile(lib / "llhttp/LICENSE-MIT", licenses / "UxPlay-llhttp-LICENSE-MIT")
replace(lib / "raop_handlers.h", "uint64_t type;\n            plist_get_uint_val", "uint64_t type = 0;\n            plist_get_uint_val")
replace(lib / "raop_handlers.h", "case 110: {", """case 110:
                http_response_init(response, "RTSP/1.0", 461, "Unsupported Transport");
                http_response_set_disconnect(response, 1);
                break;
            case 0xffff: {""")
# Remove the unreachable mirroring setup, keeping no video listener in this build.
handlers = lib / "raop_handlers.h"
source = handlers.read_text()
start = source.index("            case 0xffff:")
end = source.index("            case 96:", start)
handlers.write_text(source[:start] + source[end:])
source = handlers.read_text()
start = source.index("    plist_t displays_node")
end = source.index("\n finished:", start)
handlers.write_text(source[:start] + source[end:])
replace(handlers, "if (raop->callbacks.audio_get_format) {", """if (conn->raop_rtp && raop->callbacks.audio_get_format) {
                    char peer[64] = {0};
                    utils_ipaddress_to_string(conn->remotelen, conn->remote, conn->zone_id,
                                              peer, sizeof(peer));
                    raop->callbacks.audio_peer(raop->callbacks.cls, peer);""")
replace(lib / "raop.h", "    void  (*audio_process)", "    void (*audio_peer)(void *cls, const char *peer);\n    void  (*audio_process)")
replace(lib / "raop.c", "if (!cseq && !raop->hls_support && !ble) {", "if (!cseq && !ble) {")
replace(lib / "raop.c", """    if (raop->callbacks.conn_destroy) {
        raop->callbacks.conn_destroy(raop->callbacks.cls);
    }
""", "")
replace(lib / "raop.c", "        raop_rtp_destroy(conn->raop_rtp);", """        raop_rtp_destroy(conn->raop_rtp);
        raop->callbacks.conn_destroy(raop->callbacks.cls);""")
replace(handlers, "            conn->raop_rtp = NULL;", """            conn->raop_rtp = NULL;
            raop->callbacks.conn_destroy(raop->callbacks.cls);""")
replace(handlers, "    // Parsing bplist", """    if (!data || data_len < 8 || data_len > 16384) {
        http_response_init(response, "RTSP/1.0", 400, "Invalid SETUP");
        http_response_set_disconnect(response, 1);
        return;
    }
    // Parsing bplist""")
replace(handlers, "                    raop->callbacks.audio_get_format(raop->callbacks.cls,", """                    if (usingScreen || (ct != 2 && ct != 4 && ct != 8) ||
                        uint_val > 2048) {
                        http_response_init(response, "RTSP/1.0", 461, "Unsupported Audio Transport");
                        http_response_set_disconnect(response, 1);
                        break;
                    }
                    raop->callbacks.audio_get_format(raop->callbacks.cls,""")
replace(handlers, "    plist_t req_ekey_node =", """    if (!req_root_node || !PLIST_IS_DICT(req_root_node)) {
        plist_free(req_root_node);
        http_response_init(response, "RTSP/1.0", 400, "Invalid SETUP");
        http_response_set_disconnect(response, 1);
        return;
    }
    plist_t req_ekey_node =""")
replace(handlers, "        // The first SETUP call that initializes keys and timing", """        uint64_t iv_len = 0, key_len = 0;
        plist_get_data_ptr(req_eiv_node, &iv_len);
        plist_get_data_ptr(req_ekey_node, &key_len);
        if (iv_len != 16 || key_len != 72 || conn->raop_rtp) {
            plist_free(req_root_node);
            plist_free(res_root_node);
            http_response_init(response, "RTSP/1.0", 400, "Invalid session keys");
            http_response_set_disconnect(response, 1);
            return;
        }
        // The first SETUP call that initializes keys and timing""")
replace(handlers, "        logger_log(raop->logger, LOGGER_DEBUG, \"fairplay_decrypt ret = %d\", ret);", """        if (ret != 0) {
            plist_free(req_root_node);
            plist_free(res_root_node);
            http_response_init(response, "RTSP/1.0", 400, "FairPlay setup required");
            http_response_set_disconnect(response, 1);
            return;
        }""")
replace(lib / "http_request.c", "    int complete;", """    int complete;
    size_t received;
    size_t first_line_size;
    char first_line[4096];""")
replace(lib / "http_request.c", "    strncpy(request->protocol, at + length + 1, 8);\n", "")
replace(lib / "http_request.c", "    int ret = llhttp_execute", """    if (datalen < 0 || (size_t)datalen > 1100000 - request->received) {
        request->parser.error = HPE_USER;
        return HPE_USER;
    }
    request->received += datalen;
    if (!request->protocol[0]) {
        for (int i = 0; i < datalen; ++i) {
            if (request->first_line_size == sizeof(request->first_line) - 1) {
                request->parser.error = HPE_USER;
                return HPE_USER;
            }
            request->first_line[request->first_line_size++] = data[i];
            if (data[i] != '\\n') continue;
            char *version = strrchr(request->first_line, ' ');
            if (!version || (strcmp(version, " RTSP/1.0\\r\\n") &&
                             strcmp(version, " HTTP/1.1\\r\\n") &&
                             strcmp(version, " HTTP/1.0\\r\\n"))) {
                request->parser.error = HPE_USER;
                return HPE_USER;
            }
            memcpy(request->protocol, version + 1, 8);
            break;
        }
    }
    int ret = llhttp_execute""")
for callback in ("on_header_field", "on_header_value", "on_url"):
    replace(lib / "http_request.c", f"{callback}(llhttp_t *parser, const char *at, size_t length)\n{{",
            f"{callback}(llhttp_t *parser, const char *at, size_t length)\n{{\n"
            "    if (((http_request_t *)parser->data)->received > 16384) return HPE_USER;")
replace(lib / "httpd.c", "assert (local_zone_id == remote_zone_id);", """if (local_zone_id != remote_zone_id) {
        CLOSESOCKET(fd);
        return 0;
    }""")
replace(lib / "fairplay_playfair.c", "    int mode = req[14];", "    int mode = req[14];\n    if (mode > 3) return -1;")
replace(lib / "fairplay_playfair.c", "    memcpy(fp->keymsg, req, 164);", """    if (req[12] > 3) return -1;
    memcpy(fp->keymsg, req, 164);""")
replace(lib / "raop_rtp.c", "        tv.tv_usec = 5000;", """        /* Packets wake select immediately. Control changes need no 200 Hz idle wakeup. */
        tv.tv_usec = 100000;""")
replace(lib / "raop_rtp.c", "            int type_c = packet[1] & ~0x80;", """            if (packetlen < 2) continue;
            int type_c = packet[1] & ~0x80;""")
replace(handlers, "if (conn->raop_rtp) {\n            raop_rtp_set_coverart", "if (conn->raop_rtp && data && datalen > 0 && datalen <= 1048576) {\n            raop_rtp_set_coverart")
replace(handlers, "if (conn->raop_rtp) {\n            raop_rtp_set_metadata", "if (conn->raop_rtp && data && datalen > 0 && datalen <= 65536) {\n            raop_rtp_set_metadata")
replace(lib / "httpd.c", "    local_saddrlen = sizeof(local_saddr);", """    int keepalive = 1, idle = 30, interval = 5, probes = 3;
    struct timeval send_timeout = { 2, 0 };
    if (fd >= FD_SETSIZE) {
        CLOSESOCKET(fd);
        return 0;
    }
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &keepalive, sizeof(keepalive));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &probes, sizeof(probes));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout));
    local_saddrlen = sizeof(local_saddr);""")
replace(lib / "httpd.c", '#include "httpd.h"', '#include <netinet/tcp.h>\n#include "httpd.h"')
replace(lib / "netutils.h", "#endif", """int netutils_same_peer(void *first, void *second);
int netutils_is_local_peer(void *local, void *peer);
#endif""")
replace(lib / "netutils.c", '#include "compat.h"', """#include <ifaddrs.h>
#include "compat.h"
#include "netutils.h"

int netutils_same_peer(void *first, void *second) {
    int n1 = 0, n2 = 0;
    unsigned int zone1 = 0, zone2 = 0;
    unsigned char *a = netutils_get_address(first, &n1, &zone1, NULL);
    unsigned char *b = netutils_get_address(second, &n2, &zone2, NULL);
    return a && b && n1 == n2 && memcmp(a, b, n1) == 0;
}

int netutils_is_local_peer(void *local, void *peer) {
    int n = 0, allowed = 0;
    unsigned int zone = 0;
    unsigned char *remote = netutils_get_address(peer, &n, &zone, NULL);
    struct ifaddrs *interfaces;
    if (!remote || getifaddrs(&interfaces) != 0) return 0;
    for (struct ifaddrs *it = interfaces; it && !allowed; it = it->ifa_next) {
        if (!it->ifa_addr || !it->ifa_netmask || !netutils_same_peer(local, it->ifa_addr)) continue;
        int address_len = 0, mask_len = 0;
        unsigned char *address = netutils_get_address(it->ifa_addr, &address_len, &zone, NULL);
        unsigned char *mask = netutils_get_address(it->ifa_netmask, &mask_len, &zone, NULL);
        if (!mask || address_len != n || mask_len != n) continue;
        int i = 0, mask_present = 0;
        for (; i < n; ++i) {
            mask_present |= mask[i];
            if ((address[i] & mask[i]) != (remote[i] & mask[i])) break;
        }
        allowed = (i == n && mask_present);
    }
    freeifaddrs(interfaces);
    return allowed;
}""")
replace(lib / "httpd.c", "    ret = httpd_add_connection(httpd, fd,", """    if (!netutils_is_local_peer(&local_saddr, &remote_saddr)) {
        CLOSESOCKET(fd);
        return 0;
    }
    ret = httpd_add_connection(httpd, fd,""")
replace(lib / "raop_rtp.c", "                if (packetlen > 0) {\n                    memcpy(&raop_rtp->control_saddr", """                if (packetlen < 2 || !netutils_same_peer(&saddr, &raop_rtp->remote_saddr)) continue;
                if (packetlen > 0) {
                    memcpy(&raop_rtp->control_saddr""")
replace(lib / "raop_rtp.c", "                packetlen = recvfrom(raop_rtp->csock, (char *)packet, sizeof(packet), 0, NULL, NULL);",
        """                saddrlen = sizeof(saddr);
                packetlen = recvfrom(raop_rtp->csock, (char *)packet, sizeof(packet), 0,
                                     (struct sockaddr *)&saddr, &saddrlen);
                if (packetlen < 2 || !netutils_same_peer(&saddr, &raop_rtp->remote_saddr)) continue;""")
replace(lib / "raop_rtp.c", "            packetlen = recvfrom(raop_rtp->dsock, (char *)packet, sizeof(packet), 0, NULL, NULL);",
        """            packetlen = recvfrom(raop_rtp->dsock, (char *)packet, sizeof(packet), 0,
                                 (struct sockaddr *)&saddr, &saddrlen);
            if (packetlen < 12 || !netutils_same_peer(&saddr, &raop_rtp->remote_saddr)) continue;""")
replace(lib / "raop_ntp.c", "            response_len = recvfrom(raop_ntp->tsock, (char *)response, sizeof(response), 0, NULL, NULL);\n            if (response_len < 0) {",
        """            struct sockaddr_storage peer;
            socklen_t peer_len = sizeof(peer);
            response_len = recvfrom(raop_ntp->tsock, (char *)response, sizeof(response), 0,
                                    (struct sockaddr *)&peer, &peer_len);
            if (response_len < 32 || !netutils_same_peer(&peer, &raop_ntp->remote_saddr) ||
                memcmp(response + 8, request + 24, 8)) {""")

bridge = cpp / "native_bridge.cpp"
replace(bridge, "__android_log_print(prio, TAG, \"%s\", msg);", "(void)prio; (void)msg;")
replace(bridge, "        LOGE(\"raop_init2 failed: %d\", ret);", """        env->ReleaseStringUTFChars(keyFile, keyfile_c);
        env->ReleaseStringUTFChars(name, name_c);
        raop_destroy(ctx->raop);
        android_callbacks_destroy(&ctx->cb_ctx, env);
        delete ctx;
        return 0;""")
replace(bridge, "        LOGE(\"dnssd_init failed: %d\", dns_err);", """        env->ReleaseStringUTFChars(keyFile, keyfile_c);
        env->ReleaseStringUTFChars(name, name_c);
        raop_destroy(ctx->raop);
        android_callbacks_destroy(&ctx->cb_ctx, env);
        delete ctx;
        return 0;""")

callbacks = cpp / "android_raop_callbacks.c"
replace(callbacks, "#include <time.h>", """#include <time.h>
#include <jni.h>
#include <pthread.h>

static pthread_key_t attached_thread;
static pthread_once_t attach_once = PTHREAD_ONCE_INIT;
static void detach_thread(void *value) {
    JavaVM *vm = value;
    (*vm)->DetachCurrentThread(vm);
}
static void init_attach_key(void) {
    pthread_key_create(&attached_thread, detach_thread);
}""")
replace(callbacks, "        (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);", """        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != JNI_OK) return NULL;
        pthread_once(&attach_once, init_attach_key);
        pthread_setspecific(attached_thread, ctx->jvm);""")
replace(callbacks, "        (*env)->ExceptionDescribe(env);\n", "")
source = callbacks.read_text()
start = source.index("    if (t == RESET_TYPE_HLS_CONN_CLOSED)")
end = source.index("    if (t == RESET_TYPE_HLS_SHUTDOWN", start)
callbacks.write_text(source[:start] + source[end:])
replace(callbacks, "    cbs->on_video_wait_ready = _video_wait_ready;\n", "")
header = cpp / "android_raop_callbacks.h"
replace(header, "    jmethodID on_audio_format;", """    jmethodID on_audio_format;
    jmethodID on_audio_packet;
    jmethodID on_audio_flush;
    jmethodID on_audio_peer;
    int64_t next_packet_notice; /* RTP receive thread only */""")
replace(callbacks, "    ctx->on_video_data =", """    ctx->on_audio_packet = (*env)->GetMethodID(env, cls, "onAudioPacket", "()V");
    ctx->on_audio_flush = (*env)->GetMethodID(env, cls, "onAudioFlush", "()V");
    ctx->on_audio_peer = (*env)->GetMethodID(env, cls, "onAudioPeer", "(Ljava/lang/String;)V");
    ctx->on_video_data =""")
replace(callbacks, "    audio_engine_decode(ctx->audio_engine,", """    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    int64_t ms = (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
    if (ms >= ctx->next_packet_notice) {
        ctx->next_packet_notice = ms + 1000;
        JNIEnv *env = _get_env(ctx);
        if (env) (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_packet);
    }
    audio_engine_decode(ctx->audio_engine,""")
replace(callbacks, 'static void _audio_flush(void *cls) { LOGI("audio_flush"); }', """static void _audio_flush(void *cls) {
    android_callback_ctx_t *ctx = cls;
    ctx->next_packet_notice = 0;
    JNIEnv *env = _get_env(ctx);
    if (env) (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_flush);
}

static void _audio_peer(void *cls, const char *peer) {
    android_callback_ctx_t *ctx = cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring address = (*env)->NewStringUTF(env, peer);
    if (!address) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_peer, address);
    (*env)->DeleteLocalRef(env, address);
}""")
replace(callbacks, "    cbs->audio_process =", "    cbs->audio_peer = _audio_peer;\n    cbs->audio_process =")
for name, limit in (("metadata", 65536), ("coverart", 1048576)):
    replace(callbacks, f"static void _audio_set_{name}(void *cls, const void *buf, int len) {{",
            f"static void _audio_set_{name}(void *cls, const void *buf, int len) {{\n    if (len > {limit}) return;")
replace(callbacks, "    jstring jdacp =", """    if (!dacp_id || !active_remote ||
        strspn(dacp_id, "0123456789abcdefABCDEF") != strlen(dacp_id) ||
        strlen(dacp_id) > 16 || strlen(active_remote) > 10 ||
        strspn(active_remote, "0123456789") != strlen(active_remote)) return;
    jstring jdacp =""")
