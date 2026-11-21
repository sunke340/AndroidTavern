#!/usr/bin/env bash
# 在**没有 Android SDK 的机器上**验证 APK 的签名方案与证书指纹。
#
# 为什么需要它：本机没装 aapt2/apksigner，而"手机上能不能覆盖安装"完全取决于签名证书。
# 每次发 APK 前用它确认「这个包签的是仓库里那把固定钥匙」，而不是靠"应该没问题"。
#
# 原理：用 AGP 自带的 apksig 库（在 Gradle 缓存里）跑一遍 v1/v2/v3 校验并打印证书指纹。
# 用法：tools/verify-apk.sh dist/app-debug.apk [期望的 SHA-256 指纹]
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

APK="${1:?用法: tools/verify-apk.sh <apk> [期望指纹]}"
EXPECTED="${2:-}"

JAVA_HOME="${JAVA_HOME:-$PWD/.toolchain/jdk-17.0.21+9}"
APKSIG=$(find .toolchain/gradle-home/caches -name 'apksig-*.jar' 2>/dev/null | grep -v sources | sort | tail -1)

if [ -z "$APKSIG" ]; then
  echo "找不到 apksig jar（先在本机跑过一次 gradle，缓存里才有）" >&2
  exit 2
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/VerifyApk.java" <<'JAVA'
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;

public class VerifyApk {
    public static void main(String[] args) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        System.out.println("verified=" + result.isVerified()
            + " v1=" + result.isVerifiedUsingV1Scheme()
            + " v2=" + result.isVerifiedUsingV2Scheme()
            + " v3=" + result.isVerifiedUsingV3Scheme());

        List<X509Certificate> certs = result.getSignerCertificates();
        if (certs.isEmpty()) {
            System.out.println("没有签名证书");
            System.exit(1);
        }
        for (X509Certificate cert : certs) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) hex.append(':');
                hex.append(String.format("%02X", digest[i]));
            }
            System.out.println("subject=" + cert.getSubjectX500Principal().getName());
            System.out.println("sha256=" + hex);
        }
        for (ApkVerifier.IssueWithParams issue : result.getErrors()) {
            System.out.println("error=" + issue);
        }
        System.exit(result.isVerified() ? 0 : 1);
    }
}
JAVA

"$JAVA_HOME/bin/javac" -cp "$APKSIG" -d "$TMP" "$TMP/VerifyApk.java" || exit 3
OUT=$("$JAVA_HOME/bin/java" -cp "$APKSIG:$TMP" VerifyApk "$APK")
CODE=$?
echo "$OUT"
[ "$CODE" -ne 0 ] && exit "$CODE"

if [ -n "$EXPECTED" ]; then
  WANT=$(echo "$EXPECTED" | tr -d ':' | tr 'a-f' 'A-F')
  GOT=$(echo "$OUT" | sed -n 's/^sha256=//p' | head -1 | tr -d ':' | tr 'a-f' 'A-F')
  if [ "$WANT" != "$GOT" ]; then
    echo "签名不匹配：期望 $WANT，实际 $GOT" >&2
    exit 4
  fi
  echo "签名与期望一致"
fi
