import urllib.request, re, sys, gzip
UAS={"app":"Mozilla/5.0 (Linux; Android) PersonalNewsArchive/1.0 (personal news reader)",
     "browser":"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"}
for line in open(sys.argv[1]):
    name,url=line.strip().split('|',1)
    out=[]
    for k,ua in UAS.items():
        try:
            r=urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":ua,"Accept":"application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8"}),timeout=20)
            b=r.read(); 
            if b[:2]==b'\x1f\x8b': b=gzip.decompress(b)
            t=b.decode('utf-8','replace')
            items=len(re.findall(r'<item[\s>]|<entry[\s>]|<url>',t))
            d=re.findall(r'<(?:pubDate|updated|news:publication_date|dc:date|lastmod)>([^<]+)<',t)[:1]
            out.append(f"{k}:{r.status} {r.headers.get('Content-Type','')[:30]} final={r.url if r.url!=url else '='} items={items} newest={d[0][:25] if d else '-'} start={t.lstrip()[:40]!r}")
        except Exception as e:
            out.append(f"{k}:ERR {str(e)[:70]}")
    print(f"{name} {url}\n   "+"\n   ".join(out))
