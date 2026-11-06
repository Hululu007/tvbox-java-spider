#!/usr/bin/env python3
import json, ssl, time, urllib.request, urllib.parse
from collections import Counter

CTX = ssl.create_default_context()
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

def fetch(url):
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=CTX))
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, */*"})
    t0 = time.time()
    try:
        with op.open(req, timeout=15) as r:
            return r.status, round(time.time()-t0, 2), r.read().decode("utf-8","replace")
    except Exception as e:
        return None, round(time.time()-t0, 2), str(e)

for KW in ["流浪地球", "电影", "凡人修仙传"]:
    print(f"\n===== q={KW} =====")
    st, dt, body = fetch("https://www.yunso.net/api/opensearch.php?q=" + urllib.parse.quote(KW))
    print(f"HTTP {st} {dt}s len={len(body)}")
    try:
        j = json.loads(body)
        data = j.get("Data") or []
        print(f"Data 条数={len(data)}")
        if data:
            print("  字段:", list(data[0].keys()))
            prov = Counter(d.get("Scrurlname","?") for d in data)
            print("  Scrurlname 分布:", dict(prov))
            from urllib.parse import urlparse
            hosts = Counter(urlparse(d.get("Scrurl","")).netloc for d in data)
            print("  域名分布:", dict(hosts))
            for d in data[:5]:
                print(f"    {d.get('Scrurlname')} | {d.get('ScrName','')[:30]} | {d.get('Scrurl','')[:60]}")
    except Exception as e:
        print("  parse_err:", str(e)[:60], "head:", body[:120])
