#!/usr/bin/env python3
"""赠送版（gift build）出厂断言 —— 在 CI 里跑在「打包完成」和「发布」之间。

为什么需要它：赠送版和普通版共用同一棵树，只靠一个环境变量 WEBHTV_GIFT_MODE 区分。
环境变量漏传不会有任何编译错误 —— 出来的就是一个「资产名叫 -gift、但更新器仍然指向
我们仓库」的包，静默发出去。所以这里不看意图，只看产物：解包，检查字面量。

断言（每条对应一个具体的失败模式）：
  1. 我们**本仓库**的路径一条都不许出现 ⇒ 失败模式：WEBHTV_GIFT_MODE 没生效，或者开关地址
     被填回了本仓库。判据是**完整仓库路径** "yilishawk/webhtv-android6"（理由见下）。
  2. 占位仓库名必须在                    ⇒ 正向对照，证明上面那条不是因为「整个更新器被摇掉」
                                            或者「dex 是空的」才「干净」
  3. 开关地址必须在                      ⇒ 失败模式：WEBHTV_GATE_URLS 没注入。判据是 gate_urls
                                            里的**每一条完整 URL**（由 --gate-urls 传入）。
                                            ⚠️ build.gradle 对赠送版强制要求它非空（否则抛异常
                                            不出包），所以这条同时也是「那个断言没被绕过」的旁证。
  4. applicationId 后缀必须在            ⇒ 失败模式：后缀为空，赠送版和凯哥自己的包
                                            同 id 同签名，装上去会直接覆盖

⛔ 判据为什么是「完整仓库路径」，而不是 "github.com/<repo>"，也不是 "yilishawk"：
  用完整路径 "yilishawk/webhtv-android6" 一条就覆盖**所有**形式：
      github.com/<repo>/releases/...        更新器派生 URL
      github.com/<repo>                     设置页「打开项目主页」按钮
      api.github.com/repos/<repo>/...       更新器 API 侧
      gh/<repo>@main/...                    jsDelivr 形式的开关源（旧形态）
      raw.githubusercontent.com/<repo>/...  raw 形式的开关源（旧形态）
  为什么不用更短的两个：
    * "github.com/<repo>" 抓不到 jsDelivr 那种 "gh/<repo>@main/..." —— 而开关地址一旦被填回
      本仓库，最可能就是这个形态；
    * "yilishawk" 又会误报：开关文件放在**另一个**账号下，而那个账号的用户名完全可能也叫
      yilishawk，那样开关 URL 里就合法地含有它。
  完整路径正好在两者之间：够全，且不会误报。
  ⚠️ 这条判据只在开关**不在本仓库**上时才成立 —— 开关还挂在本仓库的那些天，开关 URL 本身
  就含完整路径，用它判会永远红。

⛔ 第 3 条用**整条 URL**，不是 host。反过来的直觉是错的：host 会**假阳性** —— 实测普通包里
  本来就有 raw.githubusercontent.com（卡拉OK 歌词 / TTML 歌词 / GitRawUrlResolver /
  WebHomeRawAdapter 那几处，R8 之后还剩 3 个），所以「host 出现了」根本不能证明开关地址真的
  注入了。而整条 URL **不存在假阴性**：dex 里的字面量就是 BuildConfig.REMOTE_GATE_URLS 的
  原值，也就是本次构建传入的 gate_urls —— 判据和被测对象同源，必然匹配。

⛔ 也不能假设「6 条 URL 都在包里」：GITHUB_LATEST / CNB 这两条在本 fork 里**没有调用者**，
R8 会把方法连同字面量一起摇掉。实测 2026-09-19 的 leanback-armeabi_v7a-a6.apk 里只有 5 条。
所以这里只要求「出现过」，不要求条数。

用法:
    python3 verify-gift-build.py --app-id <id> --gate-urls "$WEBHTV_GATE_URLS" dist/*.apk
    python3 verify-gift-build.py --app-id <id> --gate-needle https://example.com/status.json dist/*.apk
    python3 verify-gift-build.py --selftest          # 自检：伪造好/坏包，必须分辨出来
"""
import argparse
import os
import re
import sys
import tempfile
import zipfile

REAL_REPO = "yilishawk/webhtv-android6"
# 赠送版里禁止出现的字面量。用完整仓库路径，一条覆盖上面列的全部形式。
FORBIDDEN_NEEDLES = [REAL_REPO]
PLACEHOLDER_REPO = "gift-build/has-no-update"

DEX_RE = re.compile(r"classes\d*\.dex")


def read_dex(z):
    names = sorted(n for n in z.namelist() if DEX_RE.fullmatch(n))
    return names, b"".join(z.read(n) for n in names)


def needles_from_gate_urls(gate_urls):
    """gate_urls 是 '|' 分隔的多个 URL；返回每一条（去首尾空白）。

    为什么用**整条 URL** 而不是 host —— 见模块 docstring 里那条 ⛔：host 会假阳性，
    因为项目别处本来就有 raw.githubusercontent.com。
    """
    return [u.strip() for u in gate_urls.split("|") if u.strip()]


def check_apk(path, app_id, gate_needles):
    """返回 (problems, facts)。problems 非空即判定失败。"""
    problems, facts = [], []
    try:
        with zipfile.ZipFile(path) as z:
            dex_names, dex = read_dex(z)
            manifest = z.read("AndroidManifest.xml") if "AndroidManifest.xml" in z.namelist() else b""
    except zipfile.BadZipFile as e:
        return ["不是有效的 zip/APK: %s" % e], []

    facts.append("dex 文件 %d 个，合计 %d 字节" % (len(dex_names), len(dex)))
    if not dex_names:
        problems.append("包里没有 classes*.dex —— 解包失败，后面的判据全不可信")

    for needle in FORBIDDEN_NEEDLES:
        text = needle.encode()
        count = dex.count(text)
        facts.append("%-58s x%d" % (needle, count))
        if count:
            problems.append("赠送版里不该出现本仓库的路径：%s（更新器派生 URL /「打开项目主页」"
                            "按钮 / 被填回本仓库的开关地址）" % needle)

    placeholder = PLACEHOLDER_REPO.encode()
    placeholder_count = dex.count(placeholder)
    facts.append("%-58s x%d" % (placeholder.decode(), placeholder_count))
    if not placeholder_count:
        problems.append(
            "dex 里找不到占位仓库 %s —— 说明 WEBHTV_GIFT_MODE 没生效。"
            "（这条是正向对照：没有它，上面「本仓库路径一条都没有」也可能只是因为整个更新器被摇掉了）"
            % PLACEHOLDER_REPO)

    for needle in gate_needles:
        text = needle.encode()
        count = dex.count(text)
        facts.append("gate: %s  x%d" % (needle, count))
        if not count:
            problems.append("dex 里找不到开关地址 %s —— WEBHTV_GATE_URLS 没注入或传错了，"
                            "这个包读不到开关" % needle)

    # applicationId 在二进制 AndroidManifest.xml 的字符串池里。实测 aapt2 写的是 UTF-16LE
    # （2026-09-19 的 -a6 包：UTF-8 命中 0 次，UTF-16LE 命中 13 次），但别赌编码，两种都查。
    if app_id:
        hits = manifest.count(app_id.encode("utf-8")) + manifest.count(app_id.encode("utf-16-le"))
        facts.append("%-58s x%d" % ("manifest: " + app_id, hits))
        if not hits:
            problems.append("AndroidManifest.xml 里找不到 applicationId=%s —— 后缀没生效，"
                            "这个包装上去会覆盖同 id 的正式版" % app_id)
    return problems, facts


def selftest():
    """伪造好/坏几个包，确认工具能分辨 —— 别用一个没被验证过的绿灯。"""
    print("=== SELFTEST ===")
    app_id = "com.fongmi.android.tv.gift"
    # 赠送版的开关文件放在**另一个**账号下（这里用一个明显虚构的）。
    gate_url = "https://raw.githubusercontent.com/other-account/webhtv-gift-status/main/status.json"
    gate = (gate_url + "\n").encode()
    # 一个真实赠送版应该长这样：更新器指向占位仓库、开关指向别的账号、**没有本仓库的路径**。
    good_dex = (
        b"Lcom/fongmi/android/tv/BuildConfig;\n"
        b"https://github.com/gift-build/has-no-update/releases/download\n"
        b"https://api.github.com/repos/gift-build/has-no-update/releases/tags\n"
        + gate
    )
    cases = [
        # 一个真正合格的赠送版
        ("good", good_dex, True),
        # 更新器闸门没生效
        ("updater-leak",
         good_dex + b"\nhttps://github.com/yilishawk/webhtv-android6/releases/download\n", False),
        # 「打开项目主页」按钮没被置空/隐藏（旧的 /releases 判据捕不到它）
        ("project-url-leak",
         good_dex + b"\nhttps://github.com/yilishawk/webhtv-android6\n", False),
        # ⭐ 开关地址被填回本仓库 —— 这正是「完整仓库路径」判据存在的理由：
        #    这个形态不含 "github.com/<repo>"，旧的判据会放过它。
        ("gate-on-own-repo",
         good_dex + b"\nhttps://cdn.jsdelivr.net/gh/yilishawk/webhtv-android6@main/gift/status.json\n", False),
        # 正向对照失效：占位仓库不见了
        ("no-placeholder",
         good_dex.replace(b"gift-build/has-no-update", b"someone/else"), False),
        # 开关地址没注入（整条 URL 都不见了）
        ("no-gate",
         good_dex.replace(gate_url.encode(), b"https://example.invalid/gate.json"), False),
    ]

    ok = True
    with tempfile.TemporaryDirectory() as td:
        for name, dex, expect_pass in cases:
            path = os.path.join(td, "%s.apk" % name)
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("classes.dex", dex)
                z.writestr("AndroidManifest.xml", app_id.encode("utf-16-le"))
            problems, facts = check_apk(path, app_id, [gate_url])
            passed = not problems
            mark = "PASS" if passed else "FAIL"
            verdict = "OK" if passed == expect_pass else "!! 工具判断错误"
            print("  [%s] %-4s 期望 %s" % (name, mark, "PASS" if expect_pass else "FAIL"), verdict)
            for f in facts:
                print("        %s" % f)
            for p in problems:
                print("        - %s" % p)
            if passed != expect_pass:
                ok = False
    print("SELFTEST %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apks", nargs="*")
    ap.add_argument("--app-id", default="", help="期望的 applicationId，如 com.fongmi.android.tv.gift")
    ap.add_argument("--gate-urls", default="",
                    help="本次构建用的 WEBHTV_GATE_URLS 原值（多个用 | 分隔）。每一条都必须出现在 dex 里。")
    ap.add_argument("--gate-needle", default="",
                    help="直接指定一条开关地址作为判据，覆盖 --gate-urls（手工排查时用）。")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.apks:
        ap.print_help()
        return 2
    if not args.app_id:
        print("::error::--app-id 是必填的（否则第 4 条断言会静默失效）", file=sys.stderr)
        return 2

    needles = [args.gate_needle] if args.gate_needle else needles_from_gate_urls(args.gate_urls)
    if not needles:
        print("::error::必须给 --gate-urls（推荐）或 --gate-needle —— 否则第 3 条断言会静默失效",
              file=sys.stderr)
        return 2

    failed = 0
    for path in args.apks:
        print("=" * 74)
        print(os.path.basename(path))
        problems, facts = check_apk(path, args.app_id, needles)
        for f in facts:
            print("  " + f)
        if problems:
            failed += 1
            for p in problems:
                print("  !! %s" % p)
        else:
            print("  OK —— 全部断言通过")
    if failed:
        print("\n%d/%d 个包没通过赠送版断言" % (failed, len(args.apks)))
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
