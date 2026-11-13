from tv_remote.lanfind import parse_arp, pick

ARP = """
Interface: 192.168.68.61 --- 0x16
  Internet Address      Physical Address      Type
  192.168.68.1          a8-6e-84-f6-1d-77     dynamic
  192.168.68.69         58-fd-b1-c2-7b-ee     dynamic
  192.168.68.80         80-6d-71-8d-26-72     dynamic
  192.168.68.255        ff-ff-ff-ff-ff-ff     static
  224.0.0.22            01-00-5e-00-00-16     static

Interface: 192.168.56.1 --- 0x9
  192.168.56.40         58-fd-b1-c2-7b-ee     dynamic
"""
MAC = "58:FD:B1:C2:7B:EE"


def test_parse_arp_reads_windows_output():
    rows = parse_arp(ARP)
    assert ("192.168.68.69", MAC) in rows and ("192.168.68.1", "A8:6E:84:F6:1D:77") in rows


def test_pick_finds_the_tv_on_the_configured_subnet_only():
    assert pick(parse_arp(ARP), MAC, "192.168.68.80") == "192.168.68.69"
    assert pick(parse_arp(ARP), "58-fd-b1-c2-7b-ee", "192.168.56.2") == "192.168.56.40"


def test_pick_refuses_unknown_same_ambiguous_and_broadcast():
    rows = parse_arp(ARP)
    assert pick(rows, "AA:BB:CC:DD:EE:01", "192.168.68.80") is None
    assert pick(rows, MAC, "192.168.68.69") is None  # already there: nothing new
    assert pick(rows + [("192.168.68.70", MAC)], MAC, "192.168.68.80") is None  # two answers: ambiguous
    assert pick(rows, "FF:FF:FF:FF:FF:FF", "192.168.68.80") is None
    assert pick([("10.1.2.3", MAC)], MAC, "192.168.68.80") is None
