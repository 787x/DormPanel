import json
import statistics as stats
from pathlib import Path

root=Path(__file__).resolve().parent
def triple(values):
    return '/'.join(f'{n:.1f}' for n in (min(values),stats.median(values),max(values)))
for scene in ('jump','release','cold','zero','native'):
    print('\nSCENE',scene,'(min/median/max ms)')
    for version in ('before','after'):
        rows=json.loads((root/f'{version}-{scene}.json').read_text(encoding='utf-8'))
        for mode in ('CLASSIC','TIME','PERIODS'):
            group=[r for r in rows if r['layout']==mode]
            if scene!='native':
                print(version,mode,'n=',len(group),'move=',triple([r['move']/1e6 for r in group]),
                      'jump=',triple([r['warmMove']/1e6 for r in group]),'up=',triple([r['up']/1e6 for r in group]))
                print(' commit=',triple([sum((e['end']-e['start'])/1e6 for e in r['events'] if e['phase']=='commit') for r in group]))
                print(' firstDraw=',triple([(r['targetDraw']-r['lock'])/1e6 for r in group if r['targetDraw']]))
            else:
                frames=sorted(f['total']/1e6 for r in group for f in r['frames'] if f['total']>0 and f['vsync']+f['total']>=r['lock'] and f['vsync']<=r['end'])
                stages=[(e['end']-e['start'])/1e6 for r in group for e in r['events'] if e['phase']=='staged-views']
                print(version,mode,'gestures=',len(group),'P95=',round(frames[int((len(frames)-1)*.95)],1),'max=',round(max(frames),1),
                      'over32=',sum(f>32 for f in frames),'/',len(frames),'stage max=',round(max(stages,default=0),1))
