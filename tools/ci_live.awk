function now(    cmd, t) {
  cmd = "date +%s"
  cmd | getline t
  close(cmd)
  return t + 0
}
function stamp(    s) {
  s = now() - t0
  return sprintf("%02d:%02d", int(s / 60), s % 60)
}
function closegroup() {
  if (open) {
    print "::endgroup::"
    open = 0
  }
}
BEGIN {
  t0 = now()
  open = 0
  tasks = 0
  failed = 0
}
/^> Task / {
  closegroup()
  name = $0
  sub(/^> Task /, "", name)
  tasks++
  if (name ~ / FAILED$/) {
    failed++
    print "::error title=Gradle task failed::" name
    print "##[error]" name
  } else {
    print "::group::[" stamp() "] #" tasks "  " name
    open = 1
  }
  fflush()
  next
}
/^(FAILURE:|BUILD |\* What went wrong|\* Try:|\* Exception is:)/ {
  closegroup()
  print
  fflush()
  next
}
/: error: / || /^e: / {
  print "::error::" $0
  fflush()
  next
}
/: warning: / || /^w: / {
  print "::warning::" $0
  fflush()
  next
}
{
  print
  fflush()
}
END {
  closegroup()
  print "Gradle finished: " tasks " tasks in " stamp()
  fflush()
}
