#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Pure, bounded Home preparation. Callbacks own I/O and durable one-shot claims."""
import math
import time

class HomeSurfaceError(RuntimeError):
    pass

def require(value, code):
    if not value: raise HomeSurfaceError(code)

def check(deadline, clock):
    now=clock()
    require(type(deadline) in (int,float) and math.isfinite(deadline) and
        type(now) in (int,float) and math.isfinite(now) and now<deadline, 'home_preflight_deadline')

def source_token(source, *, package, idle_pin, idle_perf, prove_home):
    require(type(source) is tuple and len(source)==4,'home_source_shape')
    pin,info,perf,ui=source
    require(all(type(v) is dict for v in source),'home_source_shape')
    require(info.get('ok') is True and info.get('name')=='Family Area' and
        info.get('apiLevel')==29 and info.get('devMode') is True,'home_source_identity')
    require(idle_pin(pin) is True and idle_perf(perf) is True,'home_source_busy')
    owner=pin.get('foregroundPackage');window=pin.get('foregroundWindowId')
    if owner==package and type(window) is int and window>=0:
        require(ui.get('ok') is True,'home_application_ui')
        token=prove_home(ui,window)
        require(token is not None,'home_application_unknown')
        hash(token)
        return 'home',token
    require('foregroundPackage' in pin and 'foregroundWindowId' in pin and
        owner in (None,package) and window is None,'home_source_foreign')
    require(ui==dict(httpStatus=409,error='home_ui_application_window_required'),'home_source_root_unproven')
    # The exact 409 proves an owned root outside an active application window.
    # Presence is a lifecycle proxy; interaction may precede actual window retirement.
    require(info.get('presence',{}).get('screen') in ('DREAMING','INTERACTIVE'),'home_source_presence')
    return 'owned_nonapplication',tuple((k,pin.get(k)) for k in (
        'available','pinned','covered','nativeCover','foregroundPackage','foregroundWindowId'))

class InitialHomeOnce:
    def __init__(self): self.claimed=False; self.verified=False
    def run(self, *, read_source, dispatch_home, wait_home, prove_home, package,
            idle_pin, idle_perf, deadline, clock=time.monotonic):
        require(not self.claimed,'home_preflight_consumed')
        def read():
            check(deadline,clock);source=read_source(deadline);check(deadline,clock)
            return source_token(source,package=package,idle_pin=idle_pin,idle_perf=idle_perf,prove_home=prove_home)
        first=read();second=read();require(first==second,'home_source_changed')
        if first[0]=='home': self.verified=True;return first[1]
        check(deadline,clock)
        self.claimed=True
        ack=dispatch_home(deadline)
        check(deadline,clock)
        require(type(ack) is dict and ack.get('ok') is True and
            ack.get('action')=='home' and ack.get('dispatched') is True,'home_dispatch_unconfirmed')
        token=wait_home(deadline);check(deadline,clock)
        require(token is not None,'home_destination_unproven');hash(token)
        self.verified=True
        return token
