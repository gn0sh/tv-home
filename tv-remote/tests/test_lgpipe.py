import asyncio

from tv_remote.devices import DeviceUnreachable, LgStatus, NotPaired
from tv_remote.lgpipe import handle, serve


class FakeLg:
    def __init__(self, volume=10, muted=False, fail=None):
        self.volume, self.muted, self.fail, self.calls = volume, muted, fail, []

    async def status(self):
        return LgStatus("on", self.volume, self.muted, None, None)

    async def volume_step(self, direction):
        if self.fail:
            raise self.fail
        self.calls.append(direction)
        self.volume += getattr(self, "step", 1) if direction == "up" else -1
        return self.volume

    async def set_volume(self, level):
        self.calls.append(("set", level))
        self.volume = level
        return level

    async def set_mute(self, muted):
        self.calls.append(("mute", muted))
        self.muted = muted


def run(coro):
    return asyncio.run(coro)


def test_up_and_down():
    lg = FakeLg(10)
    assert run(handle(lg, "up\n")) == "ok 11"
    assert run(handle(lg, " DOWN ")) == "ok 10"


def test_cap_at_60():
    lg = FakeLg(60)
    assert run(handle(lg, "up")) == "ok 60"
    assert lg.calls == []  # checked before stepping: no step up at the cap


def test_above_cap_is_brought_down_without_a_step():
    lg = FakeLg(75)
    assert run(handle(lg, "up")) == "ok 60"
    assert lg.calls == [("set", 60)]


def test_step_that_lands_above_cap_is_set_back():
    lg = FakeLg(59)
    lg.step = 3  # a TV that steps by more than one
    assert run(handle(lg, "up")) == "ok 60"
    assert lg.calls == ["up", ("set", 60)]


def test_unknown_level_is_never_raised():
    lg = FakeLg(None)
    assert run(handle(lg, "up")) == "error the LG did not report its volume"
    assert lg.calls == []


def test_unknown_level_can_still_go_down():
    lg = FakeLg(None)
    lg.volume_step = lambda d: _ret(lg.calls.append(d))
    assert run(handle(lg, "down")) == "ok -"
    assert lg.calls == ["down"]


async def _ret(_):
    return None


def test_failed_set_at_the_cap_is_an_error_not_a_step():
    lg = FakeLg(80)

    async def boom(level):
        raise DeviceUnreachable("lg", "192.168.68.80")

    lg.set_volume = boom
    assert run(handle(lg, "up")) == "error the LG did not answer"
    assert lg.calls == []


def test_mute_toggles():
    lg = FakeLg(muted=False)
    assert run(handle(lg, "mute")) == "ok muted"
    assert run(handle(lg, "mute")) == "ok unmuted"


def test_unknown_command_sends_nothing():
    lg = FakeLg()
    assert run(handle(lg, "power off")) == "error unknown command"
    assert run(handle(lg, "")) == "error unknown command"
    assert lg.calls == []


def test_errors_are_short_and_carry_no_detail():
    assert run(handle(FakeLg(fail=DeviceUnreachable("lg", "192.168.68.80")), "up")) == "error the LG did not answer"
    assert run(handle(FakeLg(fail=NotPaired("lg")), "up")) == "error the LG is not paired"
    out = run(handle(FakeLg(fail=RuntimeError("secret key abc 192.168.68.80")), "up"))
    assert out == "error RuntimeError"


def test_serve_until_eof():
    lines = iter(["up\n", "mute\n", ""])
    out = []

    async def read_line():
        return next(lines)

    run(serve(FakeLg(5), read_line, out.append))
    assert out == ["ok 6", "ok muted"]


class SlowLg(FakeLg):
    """A TV that takes a moment for each request, and records the highest volume it ever reached."""

    def __init__(self, volume, step=1):
        super().__init__(volume)
        self.step, self.highest = step, volume

    async def status(self):
        await asyncio.sleep(0.01)
        return await super().status()

    async def volume_step(self, direction):
        await asyncio.sleep(0.02)
        level = await super().volume_step(direction)
        self.highest = max(self.highest, level)
        return level


def test_two_concurrent_ups_at_59_never_pass_the_cap():
    lg = SlowLg(59)

    async def both():
        return await asyncio.gather(handle(lg, "up"), handle(lg, "up"))

    assert sorted(run(both())) == ["ok 60", "ok 60"]
    assert lg.volume == 60 and lg.highest == 60
    assert lg.calls == ["up"]  # the second press saw 60 and did not step


def test_a_cancel_between_step_and_clamp_still_clamps():
    lg = SlowLg(59, step=3)  # lands on 62, so the clamp is needed

    async def press_then_give_up():
        task = asyncio.ensure_future(handle(lg, "up"))
        await asyncio.sleep(0.015)  # inside volume_step
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        await asyncio.sleep(0.1)  # the shielded command runs on

    run(press_then_give_up())
    assert lg.volume == 60 and lg.calls == ["up", ("set", 60)]


def test_unknown_level_after_a_step_is_read_and_clamped():
    lg = FakeLg(59)
    lg.step = 3
    real_step = lg.volume_step

    async def step_without_level(direction):
        await real_step(direction)
        return None

    lg.volume_step = step_without_level
    assert run(handle(lg, "up")) == "ok 60"
    assert lg.volume == 60
