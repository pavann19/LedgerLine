"""Local decoder/chain regressions; synthetic wire fixtures are not runtime evidence."""
import importlib.util
from pathlib import Path
import unittest

def load(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(name+'.py'))
    value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
receiver=load('trace-otlp-receiver'); smoke=load('trace-flow-smoke')
def varint(value):
    out=bytearray()
    while value>=128: out.append((value&127)|128); value>>=7
    out.append(value); return bytes(out)
def field(number,value):
    if isinstance(value,int): return varint(number<<3)+varint(value)
    return varint((number<<3)|2)+varint(len(value))+value

def span(name,identity,parent,kind,service):
    trace=bytes.fromhex('ab'*16)
    raw=field(1,trace)+field(2,bytes.fromhex(identity))+field(4,bytes.fromhex(parent))+field(5,name.encode())+field(6,kind)
    resource=field(1,field(1,b'service.name')+field(2,field(1,service.encode())))
    return field(1,field(1,resource)+field(2,field(2,raw)))

class TraceFixtureTests(unittest.TestCase):
    def test_actual_schema_field_numbers_decode_and_validate_chain(self):
        wire=span('http.request','11'*8,'aa'*8,2,'ledger-service')+span('outbox.publish','22'*8,'11'*8,4,'ledger-service')+span('projection.process','33'*8,'22'*8,5,'projection-service')
        spans=receiver.decode_export(wire)
        result=smoke.verify_chain(spans,'ab'*16,'aa'*8,'00-'+'ab'*16+'-'+'11'*8+'-01')
        self.assertEqual('PASS',result['status']); self.assertEqual(3,len(result['chain']))
        spans[2]['parent_span_id']='ff'*8
        with self.assertRaisesRegex(AssertionError,'lost Kafka'): smoke.verify_chain(spans,'ab'*16,'aa'*8,'00-'+'ab'*16+'-'+'11'*8+'-01')
    def test_parser_rejects_truncated_or_oversized_wire(self):
        for raw in (b'\x0a\x80',b'\x0a\x7fabc',b'\x00',b'\x0b'):
            with self.assertRaises(ValueError): receiver.decode_export(raw)
        with self.assertRaisesRegex(ValueError,'Oversized'): receiver.decode_export(b'x'*(receiver.MAX_BODY+1))
    def test_missing_or_duplicate_spans_fail(self):
        with self.assertRaisesRegex(AssertionError,'http.request'): smoke.verify_chain([],'ab'*16,'aa'*8,'00-'+'ab'*16+'-'+'11'*8+'-01')
    def test_unknown_fixed_wire_fields_are_skipped(self):
        raw=field(1,bytes.fromhex('ab'*16))+field(2,bytes.fromhex('11'*8))+field(5,b'http.request')+varint((16<<3)|5)+b'\x01\x00\x00\x00'
        decoded=receiver.decode_export(field(1,field(2,field(2,raw))))
        self.assertEqual('http.request',decoded[0]['name'])

if __name__=='__main__': unittest.main()
