local event, memo = ...
local function check(value, message)
  assert(value, message)
  memo.checks = (memo.checks or 0) + 1
end
if not memo.scheduler then
  local new = (function()

__SCHEDULER_FACTORY__

  end)()
  memo.new, memo.results, memo.order, memo.round = new, {}, {}, 0
  memo.scheduler = new(function(id, value, failed)
    memo.results[#memo.results+1] = {id=id,value=value,failed=failed}
  end, 8)
  memo.move = memo.scheduler.listen('movement')
  memo.craft = memo.scheduler.listen('crafting')
end
memo.round = memo.round + 1
local q = memo.scheduler
if memo.round == 1 then
  local function task(id, kind)
    return {id=id,responseType=kind,callback=function()
      memo.order[#memo.order+1]=id
      return {accepted=id}
    end}
  end
  q.submit(task('A','movement'))
  q.submit(task('read'))
  q.submit(task('B','movement'))
  q.submit(task('C','crafting'))
  q.run()
  check(table.concat(memo.order,',') == 'A,read,B,C', 'Submission order changed')
  check(#memo.results == 1 and memo.results[1].id == 'read', 'Immediate read waited for movement')
  check(q.status().queued == 0 and q.status().waiting == 3, 'Unexpected completion queues')
elseif memo.round == 2 then
  memo.craft({started=true})
  memo.move({x=1})
  memo.move({x=2})
  check(#memo.results == 4, 'Did not drain multiple completions in one tick')
  check(memo.results[2].id == 'C' and memo.results[3].id == 'A' and memo.results[4].id == 'B', 'Results were miscorrelated')
  check(q.status().pending == 0, 'Completed tasks retained')
  check(memo.move({}) == false, 'Unexpected completion consumed a task')
  q.submit{id='failed',responseType='movement',callback=function() error('fixture failure') end}
  q.submit{id='D',responseType='movement',callback=function() return true end}
  q.run()
  check(memo.results[5].id == 'failed' and memo.results[5].failed, 'Callback failure did not return an error')
  check(q.status().waiting == 1, 'Failed callback occupied a completion slot')
elseif memo.round == 3 then
  memo.move({x=3})
  check(memo.results[6].id == 'D', 'A failed submission shifted correlation')
  check(q.status().pending == 0, 'Task retained after completion')
  local completed=0
  local broken = memo.new(function() error('fixture disconnected IPC') end, 2)
  for i=1,2 do broken.submit{id=tostring(i),callback=function() completed=completed+1; return true end} end
  check(not broken.submit{id='full',callback=function() error('must not run') end}, 'Capacity limit ignored')
  broken.run()
  check(completed == 2 and broken.status().pending == 0, 'IPC failure stopped execution or retained results')
  local gatedOrder, gatedResults, ready, invoked = {}, {}, false, false
  local gated = memo.new(function(id, value, failed) gatedResults[#gatedResults+1]={id=id,failed=failed} end, 4)
  gated.submit{id='first',ready=function() return ready end,callback=function() gatedOrder[#gatedOrder+1]='first' end}
  gated.submit{id='second',callback=function() gatedOrder[#gatedOrder+1]='second' end}
  gated.run()
  check(#gatedOrder==0 and gated.status().queued==2, 'Readiness gate allowed overtaking')
  ready=true
  gated.run()
  check(table.concat(gatedOrder,',')=='first,second', 'Readiness gate changed FIFO order')
  gated.submit{id='bad-ready',ready=function() error('readiness failure') end,callback=function() invoked=true end}
  gated.run()
  check(not invoked, 'Failed readiness executed its callback')
  check(gatedResults[3].id=='bad-ready' and gatedResults[3].failed and gated.status().pending==0, 'Readiness failure leaked a task')
  local waiting = memo.new(function() end, 1)
  waiting.listen('never')
  waiting.submit{id='old-world',responseType='never',callback=function() return true end}
  waiting.run()
  rawset(_G,'__scheduler_world_probe',waiting)
  check(waiting.status().waiting == 1, 'World-lifetime fixture has no pending task')
end
return {checks=memo.checks,round=memo.round,results=memo.results,tick=event.tick}
