#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# This source code is licensed under the MIT license found in the root LICENSE file.
import copy
import runpy
import pathlib
import unittest

N=runpy.run_path(str(pathlib.Path(__file__).with_name('portal_home_preflight.py')))
PACKAGE='com.immortal.launcher.debug'
def idle_pin(pin):
    return pin.get('available') is True and all(pin.get(k) is False for k in ('pinned','covered','nativeCover')) and all(pin.get(k)==0 for k in ('pendingCleanupCount','failedCleanupCount'))
def idle_perf(perf):return perf==dict(active=False,collectorRunning=False,activeListeners=0)
def fixture(screen='DREAMING'):
    return (dict(available=True,pinned=False,covered=False,nativeCover=False,pendingCleanupCount=0,
        failedCleanupCount=0,foregroundPackage=None,foregroundWindowId=None),
        dict(ok=True,name='Family Area',apiLevel=29,devMode=True,presence=dict(screen=screen)),
        dict(active=False,collectorRunning=False,activeListeners=0),
        dict(httpStatus=409,error='home_ui_application_window_required'))
def prove_home(ui,window):return (window,'home') if ui.get('home') is True else None
class HomePreflightTest(unittest.TestCase):
    def run_once(self,source,**overrides):
        instance=N['InitialHomeOnce']();calls=[]
        def dispatch(end):calls.append('home');return dict(ok=True,action='home',dispatched=True)
        options=dict(read_source=lambda end:source,dispatch_home=dispatch,wait_home=lambda end:(7,'home'),
            prove_home=prove_home,package=PACKAGE,idle_pin=idle_pin,idle_perf=idle_perf,deadline=10,clock=lambda:0)
        options.update(overrides)
        return instance,calls,lambda:instance.run(**options)
    def test_interactive_own_nonapplication_root_admits_one_home(self):
        instance,calls,run=self.run_once(fixture('INTERACTIVE'))
        self.assertEqual(run(),(7,'home'));self.assertEqual(calls,['home']);self.assertTrue(instance.verified)
    def test_dreaming_own_nonapplication_root_admits_one_home(self):
        instance,calls,run=self.run_once(fixture());self.assertEqual(run(),(7,'home'));self.assertEqual(calls,['home'])
    def test_existing_proven_home_needs_no_dispatch(self):
        source=fixture();source[0].update(foregroundPackage=PACKAGE,foregroundWindowId=3)
        source=list(source);source[3]=dict(ok=True,home=True);source=tuple(source)
        instance,calls,run=self.run_once(source);self.assertEqual(run(),(3,'home'));self.assertEqual(calls,[])
    def test_unproven_and_foreign_roots_never_dispatch(self):
        for error in ['home_ui_no_active_root','home_ui_foreign_window','other']:
            source=list(fixture());source[3]=dict(httpStatus=409,error=error)
            instance,calls,run=self.run_once(tuple(source))
            with self.assertRaises(RuntimeError):run()
            self.assertEqual(calls,[])
    def test_foreign_application_owner_never_dispatches(self):
        source=fixture();source[0].update(foregroundPackage='fixture.foreign',foregroundWindowId=5)
        instance,calls,run=self.run_once(source)
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(calls,[])
    def test_off_presence_is_not_admitted(self):
        instance,calls,run=self.run_once(fixture('OFF'))
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(calls,[])
    def test_pinned_covered_and_busy_sources_never_dispatch(self):
        for key in ['pinned','covered','nativeCover']:
            source=fixture();source[0][key]=True
            instance,calls,run=self.run_once(source)
            with self.assertRaises(RuntimeError):run()
            self.assertEqual(calls,[])
        for key,value in [('active',True),('collectorRunning',True),('activeListeners',1)]:
            source=fixture();source[2][key]=value
            instance,calls,run=self.run_once(source)
            with self.assertRaises(RuntimeError):run()
            self.assertEqual(calls,[])
    def test_known_application_unknown_page_is_not_tapped(self):
        source=list(fixture());source[0].update(foregroundPackage=PACKAGE,foregroundWindowId=3);source[3]=dict(ok=True)
        instance,calls,run=self.run_once(tuple(source))
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(calls,[])
    def test_source_change_between_reads_rejects_dispatch(self):
        first=fixture();second=fixture();second[0]['foregroundPackage']=PACKAGE
        samples=iter([first,second]);instance,calls,run=self.run_once(first,read_source=lambda end:next(samples))
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(calls,[])
    def test_lost_ack_consumes_the_only_attempt(self):
        events=[]
        def lost(end):events.append('home');raise ConnectionError('fixture')
        instance,calls,run=self.run_once(fixture(),dispatch_home=lost)
        with self.assertRaises(ConnectionError):run()
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(events,['home']);self.assertTrue(instance.claimed);self.assertFalse(instance.verified)
    def test_rejected_ack_does_not_retry(self):
        events=[]
        def rejected(end):events.append('home');return dict(ok=False,action='home',dispatched=False)
        instance,calls,run=self.run_once(fixture(),dispatch_home=rejected)
        with self.assertRaises(RuntimeError):run()
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(events,['home'])
    def test_deadline_reached_before_read_makes_no_request(self):
        events=[];instance,calls,run=self.run_once(fixture(),clock=lambda:10,read_source=lambda end:events.append(end))
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(events,[]);self.assertEqual(calls,[])
    def test_late_read_makes_no_dispatch(self):
        ticks=iter([0,11]);instance,calls,run=self.run_once(fixture(),clock=lambda:next(ticks))
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(calls,[])
    def test_callbacks_receive_the_same_absolute_deadline(self):
        events=[]
        def read(end):events.append(end);return fixture()
        def dispatch(end):events.append(end);return dict(ok=True,action='home',dispatched=True)
        def wait(end):events.append(end);return (7,'home')
        instance,calls,run=self.run_once(fixture(),read_source=read,dispatch_home=dispatch,wait_home=wait)
        run();self.assertEqual(events,[10,10,10,10])
    def test_no_destination_proof_is_not_success(self):
        instance,calls,run=self.run_once(fixture(),wait_home=lambda end:None)
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(calls,['home']);self.assertFalse(instance.verified)
    def test_late_destination_is_not_success(self):
        tick=[0]
        def wait(end):tick[0]=11;return (7,'home')
        instance,calls,run=self.run_once(fixture(),clock=lambda:tick[0],wait_home=wait)
        with self.assertRaises(RuntimeError):run()
        self.assertEqual(calls,['home']);self.assertFalse(instance.verified)
if __name__=='__main__':unittest.main()
