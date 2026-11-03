"""Compare retained matched runs; report changes without inventing a causal claim."""
import argparse
import json
from pathlib import Path

MATCH=('images','compose_sha256','harness_sha256','original_load_sha256','many_account_load_sha256','profile','scenario','budgets')
def read(directory,name): return json.loads((directory/name).read_text(encoding='utf-8'))
def compare(baseline,candidate):
    left,right=read(baseline,'identity.json'),read(candidate,'identity.json')
    differences=[key for key in MATCH if left.get(key)!=right.get(key)]
    host_differences=[]
    scenario='original-two-account' if left.get('scenario')!='many' else 'many-account'
    host_name=scenario+'-host.json'
    if (baseline/host_name).exists() and (candidate/host_name).exists():
        host_a,host_b=read(baseline,host_name),read(candidate,host_name)
        for key in ('ID','Name','ServerVersion','NCPU','MemTotal','Architecture','OSType','KernelVersion','Driver','CgroupVersion'):
            if host_a['docker_info'].get(key)!=host_b['docker_info'].get(key): host_differences.append(key)
        if host_a['durability']!=host_b['durability']: host_differences.append('durability')
    else: host_differences.append('host_evidence_missing')
    differences.extend('host.'+key for key in host_differences)
    result={'comparison_status' :'MATCHED' if not differences else 'UNMATCHED','mismatched_fields':differences,'baseline_source':left['source_manifest_sha256'],'candidate_source':right['source_manifest_sha256'],'baseline_result':read(baseline,'result.json')['status'],'candidate_result':read(candidate,'result.json')['status'],'profiling_status':{'baseline':read(baseline,'result.json').get('profiling_status','UNVERIFIED'),'candidate':read(candidate,'result.json').get('profiling_status','UNVERIFIED')},'original_gates':{'p95_ms':750,'p99_ms':1500,'http_fail_fraction':0.01,'convergence_seconds':180},'causal_claim':'UNVERIFIED; matching and a difference alone do not demonstrate a root cause or statistical significance','scenarios':{},'interpretation':'Matched resources/configuration and sequential runs do not exclude host contention or order effects. Repeat alternating order before generalizing.'}
    for scenario in ('original-two-account','many-account'):
        if not (baseline/(scenario+'.json')).exists() or not (candidate/(scenario+'.json')).exists(): continue
        a,b=read(baseline,scenario+'.json')['metrics'],read(candidate,scenario+'.json')['metrics']
        values={}
        for metric,fields in {'http_req_duration':('p(50)','p(95)','p(99)'), 'http_reqs':('rate',),'http_req_failed':('value',)}.items():
            for field in fields:
                before,after=a.get(metric,{}).get(field),b.get(metric,{}).get(field)
                values[metric+'.'+field]={'baseline':before,'candidate':after,'change_percent':None if before in (None,0) or after is None else (after-before)/before*100}
        result['scenarios'][scenario]=values
    result['cleanup']={'baseline':read(baseline,'cleanup.json')['status'],'candidate':read(candidate,'cleanup.json')['status']}
    return result
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',required=True,type=Path); parser.add_argument('--candidate',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args(); result=compare(args.baseline,args.candidate)
    if args.output.exists(): parser.error('Refuse to overwrite comparison')
    args.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))
    raise SystemExit(0 if result['comparison_status']=='MATCHED' and all(v=='PASS' for v in result['cleanup'].values()) else 1)
