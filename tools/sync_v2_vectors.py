"""独立 Python Sync v2 编码/校验参考与固定测试向量。仅使用标准库。"""
import argparse
import hashlib
import json
import struct
import uuid
from pathlib import Path

# T=str, N=nullable str, I=i32, L=i64, J=nullable i32.
SCHEMAS = [
    ("events", "id:T title:T start:L end:L note:T"),
    ("entries", "id:T title:T day:I startMinute:I endMinute:I location:T"),
    ("sources", "id:T displayName:T filename:T kind:T locator:N calendarName:N sha256:T importedAt:L timezones:T occurrenceCount:I firstStart:L lastEnd:L termKey:N"),
    ("imported", "id:T sourceId:T seriesId:T uid:N originalStart:L title:T start:L end:L timezone:T location:T description:T periodLabel:N"),
    ("dayAdjustments", "date:T mode:T weekday:J label:T"),
    ("classOverrides", "key:T sourceId:N seriesId:T anchorDate:N title:N weekday:J date:N periodStart:J periodEnd:J timingMode:N startMinute:J endMinute:J location:N teacher:N note:N"),
]
MAX = 16 * 1024 * 1024

def i(n): return struct.pack(">i", n)
def text(s):
    raw = s.encode("utf-8", errors="strict")
    assert len(raw) <= 1_000_000
    return i(len(raw)) + raw

def scalar(value, kind):
    if kind in ("N", "J"):
        return b"\0" if value is None else b"\1" + scalar(value, "T" if kind == "N" else "I")
    return text(value) if kind == "T" else struct.pack(">i" if kind == "I" else ">q", value)

def row(value, schema):
    return b"".join(scalar(value.get(k), t) for k,t in (f.split(":") for f in schema.split()))

def rows(values, encode): return i(len(values)) + b"".join(map(encode, values))
def order(s): return s.encode("utf-16-be")

def snapshot(c):
    s = c["state"]
    body = text(c["datasetId"]) + text(c["commitId"]) + b"\0" + struct.pack(">qq", 1, 1)
    for key,schema in SCHEMAS:
        identity = "date" if key == "dayAdjustments" else "key" if key == "classOverrides" else "id"
        body += rows(sorted(s.get(key, []), key=lambda r: order(r[identity])), lambda r: row(r, schema))
    def profile(p):
        def phase(ph):
            return text(ph["effectiveFrom"]) + rows(sorted(ph["periods"], key=lambda r:r["periodNumber"]), lambda r: row(r, "periodNumber:I start:T end:T"))
        return row(p, "termKey:T timezone:T week1Monday:T") + rows(sorted(p["phases"], key=lambda r:r["effectiveFrom"]), phase)
    body += rows(sorted(s.get("profiles", []), key=lambda p:order(p["termKey"])), profile)
    body += rows(sorted(s.get("customProfileKeys", []), key=order), text)
    return b"DPSS" + i(1) + i(len(body)) + body + hashlib.sha256(body).digest()

def encode(c):
    ids = [c[k] for k in ("datasetId", "commitId", "writerId")]
    for v in ids + c["parents"]: assert str(uuid.UUID(v)) == v
    assert len(c["parents"]) <= 64 and len(set(c["parents"])) == len(c["parents"]) and c["commitId"] not in c["parents"]
    s = snapshot(c)
    assert len(s) <= MAX
    body = b"DPC2" + i(2) + "".join(ids).encode("ascii") + rows(sorted(c["parents"]), lambda v:v.encode("ascii")) + i(len(s)) + s
    return body + hashlib.sha256(body).digest()

class Reader:
    def __init__(self, b): self.b=b; self.pos=0
    def take(self,n):
        assert 0 <= n <= len(self.b)-self.pos
        v=self.b[self.pos:self.pos+n]; self.pos+=n; return v
    def num(self,fmt="i"): return struct.unpack(">"+fmt,self.take(4 if fmt=="i" else 8))[0]
    def text(self):
        n=self.num(); assert 0 <= n <= 1_000_000
        return self.take(n).decode("utf-8",errors="strict")
    def scalar(self,kind):
        if kind in ("N","J"):
            tag=self.take(1); assert tag in (b"\0",b"\1")
            return None if tag==b"\0" else self.scalar("T" if kind=="N" else "I")
        return self.text() if kind=="T" else self.num("i" if kind=="I" else "q")
    def row(self,schema): return {k:self.scalar(t) for k,t in (f.split(":") for f in schema.split())}
    def rows(self,read,max_count):
        n=self.num(); assert 0 <= n <= max_count
        return [read() for _ in range(n)]

def decode(b):
    assert 200 <= len(b) <= MAX+8192 and hashlib.sha256(b[:-32]).digest()==b[-32:]
    r=Reader(b[:-32]); assert r.take(4)==b"DPC2" and r.num()==2
    c={k:r.take(36).decode("ascii") for k in ("datasetId","commitId","writerId")}
    c["parents"]=r.rows(lambda:r.take(36).decode("ascii"),64)
    n=r.num(); s=r.take(n); assert r.pos==len(r.b) and len(s)<=MAX
    q=Reader(s); assert q.take(4)==b"DPSS" and q.num()==1
    n=q.num(); payload=q.take(n); assert q.take(32)==hashlib.sha256(payload).digest() and q.pos==len(s)
    q=Reader(payload); assert q.text()==c["datasetId"] and q.text()==c["commitId"] and q.take(1)==b"\0" and q.num("q")==1 and q.num("q")==1
    state={}
    for (key,schema),limit in zip(SCHEMAS,[10000,10000,16,50000,1000,5000]):
        state[key]=q.rows(lambda:q.row(schema),limit)
    def profile():
        p=q.row("termKey:T timezone:T week1Monday:T")
        def phase(): return {"effectiveFrom":q.text(),"periods":q.rows(lambda:q.row("periodNumber:I start:T end:T"),30)}
        p["phases"]=q.rows(phase,128); return p
    state["profiles"]=q.rows(profile,128); state["customProfileKeys"]=q.rows(q.text,128)
    assert q.pos==len(payload)
    c["state"]=state
    assert encode(c)==b, "noncanonical or identity mismatch"
    # Semantic checks below supplement the framing checks; full domain requirements are in the spec.
    for key in ("events","entries","sources","imported","dayAdjustments","classOverrides","profiles"):
        identity={"dayAdjustments":"date","classOverrides":"key","profiles":"termKey"}.get(key,"id")
        assert len({v[identity] for v in state[key]})==len(state[key])
    for e in state["entries"]: assert e["title"].strip() and 1<=e["day"]<=7 and 0<=e["startMinute"]<e["endMinute"]<=1439
    return c

def vectors():
    dataset="11111111-1111-4111-8111-111111111111"
    writer="22222222-2222-4222-8222-222222222222"
    def make(n,state,parents=()):
        return dict(datasetId=dataset,commitId=f"00000000-0000-4000-8000-{n:012d}",writerId=writer,parents=sorted(parents),state=state)
    complex_state=dict(
        events=[dict(id="event",title="日历",start=1791763200000,end=1791765900000,note="备注😀")],
        entries=[dict(id="local",title="课程",day=2,startMinute=600,endMinute=645,location="A101")],
        sources=[dict(id="source",displayName="合成课程",filename="classes.csv",kind="webdav",locator=None,calendarName="",sha256="a"*64,importedAt=123,timezones="Asia/Shanghai",occurrenceCount=1,firstStart=1791763200000,lastEnd=1791765900000,termKey="custom")],
        imported=[dict(id="instance",sourceId="source",seriesId="series",uid=None,originalStart=1791763200000,title="导入课",start=1791763200000,end=1791765900000,timezone="Asia/Shanghai",location="",description="教师",periodLabel="[01-01节]")],
        dayAdjustments=[dict(date="2026-10-14",mode="weekday",weekday=1,label="调课")],
        classOverrides=[dict(key="I:6:source:6:series:all",sourceId="source",seriesId="series",anchorDate=None,title=None,location="",teacher="教师",periodStart=1,periodEnd=1,timingMode="linked")],
        profiles=[dict(termKey="custom",timezone="Asia/Shanghai",week1Monday="2026-09-07",phases=[dict(effectiveFrom="2026-09-07",periods=[dict(periodNumber=1,start="08:05",end="08:50")])])],customProfileKeys=["custom"])
    root=make(1,complex_state)
    import copy
    a=copy.deepcopy(complex_state); a["entries"][0]["location"]="A301"
    b=copy.deepcopy(complex_state); b["events"][0]["note"]="phone"
    left=make(2,a,[root["commitId"]]); right=make(3,b,[root["commitId"]])
    merged=copy.deepcopy(a); merged["events"]=b["events"]
    return {"empty":make(0,{}),"complex-root":root,"left":left,"right":right,"multi-parent":make(4,merged,[left["commitId"],right["commitId"]])}

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--generate",action="store_true"); args=parser.parse_args()
    folder=Path(__file__).resolve().parents[1]/"schedule-core/src/test/resources/sync-v2"
    if args.generate:
        folder.mkdir(parents=True,exist_ok=True)
        manifest={}
        for name,c in vectors().items():
            b=encode(c); (folder/(name+".dpc")).write_bytes(b)
            (folder/(name+".json")).write_text(json.dumps(c,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
            manifest[name]={"sha256":hashlib.sha256(b).hexdigest(),"bytes":len(b),"valid":True}
        broken=bytearray(encode(vectors()["complex-root"])); broken[-1]^=1
        (folder/"bad-checksum.dpc").write_bytes(broken)
        manifest["bad-checksum"]={"valid":False,"reason":"SHA-256 不匹配"}
        (folder/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    manifest=json.loads((folder/"manifest.json").read_text(encoding="utf-8"))
    for name,expected in manifest.items():
        b=(folder/(name+".dpc")).read_bytes()
        if expected["valid"]:
            decode(b); assert expected["sha256"]==hashlib.sha256(b).hexdigest()
        else:
            try: decode(b)
            except (AssertionError,ValueError,UnicodeError): pass
            else: raise AssertionError("invalid vector accepted")
    print(f"Python 独立编码/解码校验通过：{len(manifest)} 个固定向量")
if __name__=="__main__": main()
