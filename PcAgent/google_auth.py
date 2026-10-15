# -*- coding: utf-8 -*-
"""
Google カレンダー / ToDo（Tasks）を読むための「リフレッシュトークン」を取得する、最初の1回だけ使う補助プログラム。
使い方: python google_auth.py クライアントID クライアントシークレット
         （引数を省略すると、実行中に聞かれます）
ブラウザが開くので Google にログインして許可すると、同じフォルダに google_config.json ができます。
スマホの設定画面の「PCから取り込む」で、この内容を取り込めます（PC側プログラムを起動し、USBでつないでおく）。
読み取り専用（変更・削除はできない）の権限だけを求めます。
"""
import http.server
import json
import os
import secrets
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
SCOPES = "https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/tasks.readonly"


def main():
    cid = sys.argv[1] if len(sys.argv) > 1 else input("クライアントID: ").strip()
    csec = sys.argv[2] if len(sys.argv) > 2 else input("クライアントシークレット: ").strip()
    state = secrets.token_urlsafe(16)
    got = {}
    done = threading.Event()

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "code" in q or "error" in q:
                got["q"] = q
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write("<meta charset=utf-8><p>完了しました。このタブは閉じてかまいません。</p>".encode("utf-8"))
                done.set()
            else:
                self.send_response(404)
                self.end_headers()

    srv = http.server.HTTPServer(("127.0.0.1", 0), H)
    port = srv.server_address[1]
    redirect = "http://127.0.0.1:%d" % port
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": cid, "redirect_uri": redirect, "response_type": "code", "scope": SCOPES,
        "access_type": "offline", "prompt": "consent", "state": state,
    })
    print("ブラウザで許可してください。開かない場合は次のURLを開いてください:\n" + url + "\n")
    webbrowser.open(url)
    if not done.wait(300):
        print("5分以内に許可されませんでした。")
        sys.exit(1)
    srv.shutdown()
    q = got["q"]
    if "error" in q or q.get("state", [""])[0] != state:
        print("許可されませんでした:", q.get("error", ["state不一致"])[0])
        sys.exit(1)

    data = urllib.parse.urlencode({
        "code": q["code"][0], "client_id": cid, "client_secret": csec,
        "redirect_uri": redirect, "grant_type": "authorization_code",
    }).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", data=data), timeout=20) as r:
            tok = json.load(r)
    except Exception as e:
        print("トークンの取得に失敗しました:", e)
        sys.exit(1)
    if not tok.get("refresh_token"):
        print("リフレッシュトークンが返りませんでした。Googleアカウントの「サードパーティのアクセス」から このアプリを削除して、やり直してください。")
        sys.exit(1)
    path = os.path.join(HERE, "google_config.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"client_id": cid, "client_secret": csec, "refresh_token": tok["refresh_token"]}, f, indent=2)
    try:
        os.chmod(path, 0o600)
    except Exception:
        pass
    print("保存しました:", path)
    print("次に、PC側プログラムを起動してスマホをUSBでつなぎ、スマホの設定画面で「PCから取り込む」を押してください。")


if __name__ == "__main__":
    main()
