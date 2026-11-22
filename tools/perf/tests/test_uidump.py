"""uiautomator parsing: tab-bar targets, the door row, and the overflow audit (synthetic dumps)."""

import unittest

from perflib import scenarios, uidump

N = ('<node index="0" text="{t}" resource-id="" class="{c}" package="com.immortal.launcher.debug" '
     'content-desc="{d}" clickable="{k}" scrollable="{s}" bounds="{b}"{end}')


def node(b, t="", d="", c="android.view.View", k="false", s="false", kids=""):
    if kids:
        return N.format(t=t, d=d, c=c, k=k, s=s, b=b, end=">") + kids + "</node>"
    return N.format(t=t, d=d, c=c, k=k, s=s, b=b, end=" />")


def dump(*kids):
    return ("noise before\n<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
            + node("[0,0][2160,1440]", c="android.widget.FrameLayout", kids="".join(kids)) + "</hierarchy>")


HOME = dump(
    node("[2056,14][2133,91]", d="Settings", c="android.widget.Button", k="true"),
    node("[40,300][400,340]", t="Home Control"),
    node("[859,1356][937,1392]", t="Home"),
    node("[1071,1356][1139,1392]", t="Apps"),
    node("[1277,1356][1347,1392]", t="Tools"),
)


class FindTest(unittest.TestCase):
    def test_tab_bar_labels(self):
        nodes = uidump.parse(HOME)
        self.assertEqual(uidump.find(nodes, "Home").center, (898, 1374))
        self.assertEqual(uidump.find(nodes, "Apps").center, (1105, 1374))
        self.assertEqual(uidump.find(nodes, "Tools").center, (1312, 1374))
        self.assertEqual(uidump.find(nodes, "settings", prefer="top").center, (2094, 52))
        self.assertIsNone(uidump.find(nodes, "Music"))

    def test_door_row_by_content_desc_substring(self):
        cc = dump(node("[1545,198][2099,284]", d="Pantry door is open. Open 26 min", k="true"),
                  node("[1545,298][2099,384]", d="Office door is open. Open 14 days", k="true"))
        found = uidump.find(uidump.parse(cc), "door is open", exact=False, prefer="top")
        self.assertEqual(found.center, (1822, 241))

    def test_no_hierarchy(self):
        with self.assertRaises(ValueError):
            uidump.parse("ERROR: could not get idle state.")


class OverflowTest(unittest.TestCase):
    def test_flags_wide_offscreen_and_horizontal_scrollers(self):
        nodes = uidump.parse(dump(
            node("[0,100][2400,200]", t="Wide banner"),
            node("[2100,300][2300,400]", t="Clipped chip"),
            node("[0,500][2160,700]", s="true", kids=node("[0,500][900,700]", t="a") + node("[1800,500][2700,700]", t="b")),
            node("[0,800][2160,1000]", s="true", kids=node("[0,800][2160,900]", t="row") + node("[0,900][2160,1400]", t="r2")),
            node("[100,1100][300,1200]", t="Fine"),
        ))
        self.assertEqual(uidump.screen_size(nodes), (2160, 1440))
        found = {f["label"]: f["reasons"] for f in uidump.overflow(nodes)}
        self.assertIn("wider than screen", found["Wide banner"][0])
        self.assertIn("past screen edge", found["Clipped chip"][0])
        self.assertIn("horizontally scrollable", found[""])
        self.assertNotIn("Fine", found)
        self.assertNotIn("row", found)
        self.assertIn("b", found)  # the child reaching past the right edge
        self.assertEqual(sum(1 for f in uidump.overflow(nodes) if "horizontally scrollable" in f["reasons"]), 1)


class RegistryTest(unittest.TestCase):
    def test_required_scenarios_exist(self):
        for name in ("home-tabs", "cc-open", "cc-switch", "settings-open", "settings-page:world",
                     "screensaver-pinned:galleryWall", "screensaver-household", "rotation",
                     "mini-size:portrait:cc-open", "mini-size:landscape:home-tabs", "uiautomator-overflow",
                     "uiautomator-overflow:home,settings"):
            sc = scenarios.get(name)
            self.assertIn("summary", sc)
            self.assertIsInstance(sc["screenshots"], bool)

    def test_mini_size_session_and_unknowns(self):
        self.assertEqual(scenarios.get("mini-size:portrait:cc-open")["session"][0], ("mini", "portrait"))
        for bad in ("nope", "mini-size:sideways:cc-open", "mini-size:portrait", "settings-page:../x",
                    "uiautomator-overflow:kitchen", "screensaver-pinned:a b"):
            with self.assertRaises(KeyError, msg=bad):
                scenarios.get(bad)


if __name__ == "__main__":
    unittest.main()
