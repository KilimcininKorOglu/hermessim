// The playout rule of call audio (HermesSIM protocol README "Playout"): packets go in by tick (10 ms steps), one
// tick comes out per take. No locks, no ALSA, no Opus: the audio pump holds the lock, finds each packet's length
// from its Opus TOC and decodes what a take names. vectors/playout.txt is its test. GPL-3.0-or-later.
// Only the Android audio pump calls it; host builds compile it for the tests.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

pub const SLOTS: usize = 64;
pub const MAX_PACKET: usize = 400;
pub const MAX_TICKS: i32 = 6;
const MASK: u16 = (SLOTS - 1) as u16;
const MAX_GAP: u16 = 4;
const WINDOW: i32 = 200;
const MAX_DEPTH: i32 = 8;
const RESYNC: u32 = 50;

#[derive(Clone, Copy)]
struct Slot {
    valid: bool,
    tick: u16,
    ticks: i32,
    len: usize,
    data: [u8; MAX_PACKET],
}

const EMPTY: Slot = Slot {
    valid: false,
    tick: 0,
    ticks: 0,
    len: 0,
    data: [0; MAX_PACKET],
};

/// What became of a packet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Put {
    Ok,
    Late,
    Resync,
    Bad,
}

/// One tick out. Play and Fec have copied the packet into the take's buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Take {
    Silence,
    Play { tick: u16, ticks: i32, len: usize },
    /// Which tick of the last decode to play.
    Carry { index: i32 },
    Fec { tick: u16, gap: i32, len: usize },
    Plc,
}

pub struct Playout {
    slots: [Slot; SLOTS],
    pub started: bool,
    pub playing: bool,
    next: u16,
    end: u16,
    carry: i32,
    decoded: i32,
    late_run: u32,
    win_min: i32,
    win_n: i32,
    trim: i32,
    /// Ticks before playing (TUNE).
    pub prime: i32,
    /// The depth the trimming keeps (TUNE).
    pub keep: i32,
    pub received: u32,
    pub lost: u32,
    pub late: u32,
    pub dropped: u32,
}

/// a - b on the 16-bit tick circle: "empty" must never look like "65535 queued".
fn diff(a: u16, b: u16) -> i32 {
    a.wrapping_sub(b) as i16 as i32
}

impl Playout {
    pub const fn new() -> Self {
        Self {
            slots: [EMPTY; SLOTS],
            started: false,
            playing: false,
            next: 0,
            end: 0,
            carry: 0,
            decoded: 0,
            late_run: 0,
            win_min: 1000,
            win_n: 0,
            trim: 0,
            prime: 2,
            keep: 1,
            received: 0,
            lost: 0,
            late: 0,
            dropped: 0,
        }
    }

    /// Empties the player; the counters and the tuning stay.
    pub fn reset(&mut self) {
        self.clear(0);
        self.started = false;
        self.decoded = 0;
        self.late_run = 0;
        self.win_min = 1000;
        self.win_n = 0;
        self.trim = 0;
    }

    /// Ticks buffered ahead of playback, decoded ones included.
    pub fn depth(&self) -> i32 {
        diff(self.end, self.next) + self.carry
    }

    fn clear(&mut self, tick: u16) {
        for s in self.slots.iter_mut() {
            s.valid = false;
        }
        self.next = tick;
        self.end = tick;
        self.playing = false;
        self.carry = 0;
    }

    fn at(&self, tick: u16) -> Option<usize> {
        let i = (tick & MASK) as usize;
        let s = &self.slots[i];
        (s.valid && s.tick == tick).then_some(i)
    }

    /// One packet of `ticks` ticks at `tick`.
    pub fn put(&mut self, tick: u16, ticks: i32, data: &[u8]) -> Put {
        if !(1..=MAX_TICKS).contains(&ticks) || data.is_empty() || data.len() > MAX_PACKET {
            return Put::Bad;
        }
        if !self.started {
            self.started = true;
            self.next = tick;
            self.end = tick;
        }
        let mut result = Put::Ok;
        if diff(tick, self.next) < 0 {
            result = self.late_packet(tick);
            if result == Put::Late {
                return result;
            }
        }
        self.late_run = 0;
        if diff(tick, self.next) >= SLOTS as i32 - 1 {
            self.clear(tick); // far ahead: play from it
        }
        self.store(tick, ticks, data);
        result
    }

    /// A packet older than the next tick: dropped, unless 50 in a row say the sender started again.
    fn late_packet(&mut self, tick: u16) -> Put {
        self.late += 1;
        self.late_run += 1;
        if self.late_run < RESYNC {
            return Put::Late;
        }
        self.clear(tick);
        self.late_run = 0;
        Put::Resync
    }

    fn store(&mut self, tick: u16, ticks: i32, data: &[u8]) {
        let s = &mut self.slots[(tick & MASK) as usize];
        s.valid = true;
        s.tick = tick;
        s.ticks = ticks;
        s.len = data.len();
        s.data[..data.len()].copy_from_slice(data);
        let end = tick.wrapping_add(ticks as u16);
        if diff(end, self.end) > 0 {
            self.end = end;
        }
        self.received += 1;
        if !self.playing && self.depth() >= self.prime {
            self.playing = true;
        }
    }

    /// One tick out; Play and Fec copy the packet into `buf`.
    pub fn take(&mut self, buf: &mut [u8; MAX_PACKET]) -> Take {
        let out = self.pull(buf);
        if out != Take::Silence {
            self.adapt();
        }
        out
    }

    fn pull(&mut self, buf: &mut [u8; MAX_PACKET]) -> Take {
        if self.carry > 0 {
            self.carry -= 1;
            return Take::Carry {
                index: self.decoded - self.carry - 1,
            };
        }
        if !self.playing {
            return Take::Silence;
        }
        if diff(self.end, self.next) <= 0 {
            self.playing = false; // ran dry: wait for the prime again
            return Take::Silence;
        }
        let Some(i) = self.at(self.next) else {
            return self.recover(buf);
        };
        let s = self.slots[i];
        self.slots[i].valid = false;
        self.next = self.next.wrapping_add(s.ticks as u16);
        self.carry = s.ticks - 1;
        self.decoded = s.ticks;
        buf[..s.len].copy_from_slice(&s.data[..s.len]);
        Take::Play {
            tick: s.tick,
            ticks: s.ticks,
            len: s.len,
        }
    }

    /// The next tick is missing: FEC from the first later packet within 4 ticks when the gap covers its length,
    /// else one tick of concealment.
    fn recover(&mut self, buf: &mut [u8; MAX_PACKET]) -> Take {
        for gap in 1..=MAX_GAP {
            let Some(i) = self.at(self.next.wrapping_add(gap)) else {
                continue;
            };
            let s = self.slots[i];
            if s.ticks > gap as i32 {
                break;
            }
            return self.fec(s, gap as i32, buf);
        }
        self.next = self.next.wrapping_add(1);
        self.lost += 1;
        self.decoded = 1;
        Take::Plc
    }

    fn fec(&mut self, s: Slot, gap: i32, buf: &mut [u8; MAX_PACKET]) -> Take {
        self.next = self.next.wrapping_add(gap as u16);
        self.carry = gap - 1;
        self.decoded = gap;
        self.lost += gap as u32;
        buf[..s.len].copy_from_slice(&s.data[..s.len]);
        Take::Fec {
            tick: s.tick,
            gap,
            len: s.len,
        }
    }

    /// Drops one tick of buffered audio (latency trimming).
    fn drop_tick(&mut self) {
        self.dropped += 1;
        if self.carry > 0 {
            self.carry -= 1;
            return;
        }
        if let Some(i) = self.at(self.next) {
            self.slots[i].valid = false;
            self.next = self.next.wrapping_add(self.slots[i].ticks as u16);
            return;
        }
        self.next = self.next.wrapping_add(1);
    }

    /// Adaptive latency: when even a window's smallest depth was above `keep`, that much buffering was never needed,
    /// so it goes one tick per take. Above the hard cap one tick goes at every take.
    fn adapt(&mut self) {
        self.win_min = self.win_min.min(self.depth());
        self.win_n += 1;
        if self.win_n >= WINDOW {
            if self.win_min > self.keep {
                self.trim = self.win_min - self.keep;
            }
            self.win_min = 1000;
            self.win_n = 0;
        }
        if self.trim > 0 && self.depth() > self.keep {
            self.drop_tick();
            self.trim -= 1;
        }
        if self.depth() > MAX_DEPTH {
            self.drop_tick();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn describe(take: Take) -> String {
        match take {
            Take::Silence => "silence".into(),
            Take::Play { tick, .. } => format!("play {}", tick),
            Take::Carry { .. } => "carry".into(),
            Take::Fec { tick, gap, .. } => format!("fec {} {}", tick, gap),
            Take::Plc => "plc".into(),
        }
    }

    fn numbers(rest: &str) -> Vec<u32> {
        rest.split(' ').filter_map(|n| n.parse().ok()).collect()
    }

    /// Runs one line of a scenario; the failure's text when it does not match.
    fn step(p: &mut Playout, line: &str) -> Option<String> {
        let mut buf = [0u8; MAX_PACKET];
        if let Some(rest) = line.strip_prefix("put ") {
            let n = numbers(rest);
            p.put(n[0] as u16, n[1] as i32, &[0x78]);
            return None;
        }
        if let Some(want) = line.strip_prefix("take ") {
            let got = describe(p.take(&mut buf));
            return (got != want).then(|| format!("want \"{}\", got \"{}\"", want, got));
        }
        let n = numbers(line.strip_prefix("count ")?);
        let got = vec![p.received, p.lost, p.late];
        (got != n).then(|| format!("want counts {:?}, got {:?}", n, got))
    }

    #[test]
    fn the_shared_playout_scenarios_pass() {
        let mut failures = Vec::new();
        let mut scenarios = 0;
        let mut name = String::new();
        let mut p = Playout::new();
        for line in include_str!("../vectors/playout.txt").lines() {
            if line.starts_with('#') || line.is_empty() {
                continue;
            }
            if let Some(n) = line.strip_prefix("scenario ") {
                name = n.into();
                p = Playout::new();
                scenarios += 1;
                continue;
            }
            if let Some(f) = step(&mut p, line) {
                failures.push(format!("{}: {}", name, f));
            }
        }
        assert_eq!(scenarios, 14);
        assert!(failures.is_empty(), "{:#?}", failures);
    }

    #[test]
    fn a_packet_of_an_impossible_length_is_refused() {
        let mut p = Playout::new();
        assert_eq!(p.put(0, 0, &[1]), Put::Bad);
        assert_eq!(p.put(0, 7, &[1]), Put::Bad);
        assert_eq!(p.put(0, 1, &[]), Put::Bad);
        assert_eq!(p.received, 0);
    }

    #[test]
    fn a_carry_names_the_second_tick_of_a_20_ms_packet() {
        let mut p = Playout::new();
        let mut buf = [0u8; MAX_PACKET];
        p.put(0, 2, &[0x78, 1]);
        assert_eq!(p.take(&mut buf), Take::Play { tick: 0, ticks: 2, len: 2 });
        assert_eq!(buf[..2], [0x78, 1]);
        assert_eq!(p.take(&mut buf), Take::Carry { index: 1 });
    }
}
