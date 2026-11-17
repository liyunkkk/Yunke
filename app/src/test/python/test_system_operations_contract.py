"""系统与一方应用操作移植的源码契约：目录、权限标注、图标覆盖、执行接线。"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main/kotlin/io/github/mangi/eta"
CATALOG = MAIN / "agent/model/AgentPhoneToolCatalog.kt"
ICONS = MAIN / "ui/components/ToolIcons.kt"
REQUIREMENTS = MAIN / "agent/tool/AgentToolRequirements.kt"
LOCAL_TOOLS = MAIN / "agent/tool/AgentLocalTools.kt"
TOOL_CATALOG = MAIN / "agent/model/AgentToolCatalog.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


def entries():
    text = CATALOG.read_text(encoding="utf-8")
    return re.findall(r'Entry\(\s*"([a-z_]+)"', text)


def entry_block(name):
    """Entry(...) 是跨行写法，按名字切出所属块再断言字段。"""
    text = CATALOG.read_text(encoding="utf-8")
    index = text.find('Entry(\n                "%s"' % name)
    if index < 0:
        index = text.find('Entry("%s"' % name)
    if index < 0:
        return None
    end = text.find("Entry(", index + 5)
    return text[index:end if end > 0 else len(text)]


class SystemOperationsContractTest(unittest.TestCase):
    def test_catalog_entries_exist(self):
        names = entries()
        self.assertGreaterEqual(len(names), 20, "系统操作目录条目过少")
        self.assertEqual(len(names), len(set(names)), "目录里有重复工具名")

    def test_calendar_tools_do_not_require_root(self):
        text = CATALOG.read_text(encoding="utf-8")
        calendar = (
            "list_calendars", "read_calendar_event", "create_calendar_event",
            "create_calendar_events", "update_calendar_event", "delete_calendar_event",
        )
        for name in calendar:
            block = entry_block(name)
            self.assertIsNotNone(block, name)
            self.assertIn("RootRequirement.NONE", block, "%s 不应要求 Root" % name)

    def test_alarm_mutation_keeps_privileged_marking(self):
        text = CATALOG.read_text(encoding="utf-8")
        for name in ("update_alarm_time", "set_alarm_enabled", "delete_alarm"):
            block = entry_block(name)
            self.assertIsNotNone(block, name)
            self.assertIn("RootRequirement.REQUIRED", block, name)

    def test_every_entry_has_icon_and_requirement(self):
        icons = ICONS.read_text(encoding="utf-8")
        requirements = REQUIREMENTS.read_text(encoding="utf-8")
        self.assertIn("AgentPhoneToolCatalog.entries", requirements, "权限元数据未按目录登记")
        for name in entries():
            self.assertIn('"%s"' % name, icons, "%s 缺少图标映射" % name)

    def test_catalog_and_executor_are_wired(self):
        self.assertIn("AgentPhoneToolCatalog.appendTo", TOOL_CATALOG.read_text(encoding="utf-8"))
        local = LOCAL_TOOLS.read_text(encoding="utf-8")
        self.assertIn("AgentPhoneToolCatalog.names", local)
        self.assertIn("PhoneOperations.execute", local)

    def test_system_operation_permissions_declared(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        for permission in (
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.ACCESS_NOTIFICATION_POLICY",
            "android.permission.NFC",
            "android.permission.WAKE_LOCK",
        ):
            self.assertIn(permission, manifest, permission)


if __name__ == "__main__":
    unittest.main()
