"""Bounded synthetic OTLP/HTTP receiver; not a production collector.

Wire fields follow OpenTelemetry protobuf v1.5.0 official schemas:
https://github.com/open-telemetry/opentelemetry-proto/blob/v1.5.0/opentelemetry/proto/trace/v1/trace.proto
https://github.com/open-telemetry/opentelemetry-proto/blob/v1.5.0/opentelemetry/proto/collector/trace/v1/trace_service.proto
Only span identifiers/name/kind/times and service.name are decoded. Unknown
protobuf fields are skipped by wire type. No generated runtime dependency.
"""
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading

MAX_BODY=2*1024*1024
MAX_SPANS=10000

def varint(data,index):
    value=0
    for shift in range(0,70,7):
        if index>=len(data): raise ValueError('Truncated varint')
        byte=data[index]; index+=1; value|=(byte&127)<<shift
        if byte<128: return value,index
    raise ValueError('Oversized varint')

def fields(data):
    if len(data)>MAX_BODY: raise ValueError('Oversized protobuf message')
    index=0
    while index<len(data):
        tag,index=varint(data,index); field,wire=tag>>3,tag&7
        if field==0: raise ValueError('Invalid field zero')
        if wire==0: value,index=varint(data,index)
        elif wire in (1,5):
            size=8 if wire==1 else 4
            if index+size>len(data): raise ValueError('Truncated fixed field')
            value=int.from_bytes(data[index:index+size],'little'); index+=size
        elif wire==2:
            size,index=varint(data,index)
            if index+size>len(data): raise ValueError('Truncated bytes field')
            value=data[index:index+size]; index+=size
        else: raise ValueError('Unsupported protobuf wire type')
        yield field,value

def values(data,number): return [value for key,value in fields(data) if key==number]
def first(data,number,default=b''): return next((value for key,value in fields(data) if key==number),default)

def decode_export(data):
    spans=[]
    for resource_spans in values(data,1):
        service='unknown'
        resource=first(resource_spans,1)
        for attribute in values(resource,1):
            if first(attribute,1)==b'service.name': service=first(first(attribute,2),1).decode('utf-8')
        for scope in values(resource_spans,2):
            for raw in values(scope,2):
                trace,span,parent=first(raw,1),first(raw,2),first(raw,4)
                if len(trace)!=16 or len(span)!=8 or (parent and len(parent)!=8) or not any(trace) or not any(span): raise ValueError('Invalid span identity')
                spans.append({'service':service,'trace_id':trace.hex(),'span_id':span.hex(),'parent_span_id':parent.hex(),'name':first(raw,5).decode('utf-8'),'kind':first(raw,6,0),'start_time_unix_nano':first(raw,7,0),'end_time_unix_nano':first(raw,8,0)})
                if len(spans)>MAX_SPANS: raise ValueError('Span limit exceeded')
    return spans

class Receiver(BaseHTTPRequestHandler):
    records=[]; batches=[]; lock=threading.Lock()
    def log_message(self,*args): pass
    def do_GET(self):
        if self.path!='/spans': self.send_error(404); return
        with self.lock: body=json.dumps({'spans':self.records,'batches':self.batches}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        try:
            if self.path!='/v1/traces': self.send_error(404); return
            if self.headers.get('Content-Encoding') not in (None,'identity'): raise ValueError('Compression not supported by bounded fixture')
            size=int(self.headers.get('Content-Length','-1'))
            if not 0<=size<=MAX_BODY: raise ValueError('Invalid request size')
            body=self.rfile.read(size)
            if len(body)!=size: raise ValueError('Truncated request')
            spans=decode_export(body)
            with self.lock:
                if len(self.records)+len(spans)>MAX_SPANS or sum(batch['bytes'] for batch in self.batches)+size>4*1024*1024: raise ValueError('Receiver retention limit')
                self.records.extend(spans); self.batches.append({'sha256':hashlib.sha256(body).hexdigest(),'bytes':size,'spans':len(spans),'wire_base64':base64.b64encode(body).decode()})
            self.send_response(200); self.send_header('Content-Type','application/x-protobuf'); self.send_header('Content-Length','0'); self.end_headers()
        except (ValueError,UnicodeError) as error: self.send_error(400,str(error))

if __name__=='__main__': ThreadingHTTPServer(('0.0.0.0',4318),Receiver).serve_forever()
