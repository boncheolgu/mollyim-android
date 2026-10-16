import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from memoly_branding import rebrand_resources, replace_text, resource_files


class MemolyBrandingTest(unittest.TestCase):
    def test_text_is_rebranded_without_changing_external_services_or_addresses(self):
        self.assertEqual(
            replace_text("Molly은 MOLLY's molly-FOSS MollySocket https://molly.im/install support@molly.im molly.im"),
            "Memoly는 Memoly's Memoly-FOSS MollySocket https://molly.im/install support@molly.im molly.im",
        )

    def test_korean_brand_and_particles_are_updated(self):
        self.assertEqual(replace_text("몰리를 실행 Molly이 Molly은 Molly을 Molly으로 몰리소켓"),
                         "Memoly를 실행 Memoly가 Memoly는 Memoly를 Memoly로 몰리소켓")

    def test_project_links_point_to_this_fork_while_service_links_stay_unchanged(self):
        before = '''<resources>
<string name="website_url">https://molly.im</string>
<string name="install_url">https://molly.im/install</string>
<string name="HelpSettingsFragment__molly_im_website">Molly.im website</string>
<string name="mollysocket_setup_url">https://github.com/mollyim/mollysocket</string>
</resources>'''
        after = rebrand_resources(before)
        self.assertIn('>https://github.com/boncheolgu/mollyim-android</string>', after)
        self.assertIn('>https://github.com/boncheolgu/mollyim-android/releases</string>', after)
        self.assertIn('>Memoly website</string>', after)
        self.assertIn('>https://github.com/mollyim/mollysocket</string>', after)

    def test_markup_ids_placeholders_and_upstream_notices_are_preserved(self):
        before = '''<resources>
<string name="molly_title" mollyify="true">Unlock &lt;b&gt;Molly&lt;/b&gt; %1$s</string>
<string name="HelpFragment__copyright_signal_messenger">Copyright Molly Messenger</string>
<string name="preferences__donate_to_signal">Donate to Molly</string>
<string name="link"><a href="https://molly.im">Molly</a></string>
<plurals name="molly_count"><item quantity="other">Molly %d</item></plurals>
<string name="html"><![CDATA[<b>Molly</b>]]></string>
</resources>'''
        after = rebrand_resources(before)
        self.assertIn('name="molly_title" mollyify="true">Unlock &lt;b&gt;Memoly&lt;/b&gt; %1$s', after)
        self.assertIn('Copyright Molly Messenger', after)
        self.assertIn('Donate to Molly', after)
        self.assertIn('<a href="https://molly.im">Memoly</a>', after)
        self.assertIn('<item quantity="other">Memoly %d</item>', after)
        self.assertIn('<![CDATA[<b>Memoly</b>]]>', after)
        ET.fromstring(after)
        self.assertEqual(after, rebrand_resources(after))

    def test_all_shipped_resource_branding_is_current_and_xml_is_valid(self):
        root = Path(__file__).resolve().parents[2]
        for path in resource_files(root):
            with self.subTest(path=str(path.relative_to(root))):
                text = path.read_text()
                ET.fromstring(text)
                self.assertEqual(text, rebrand_resources(text))


if __name__ == "__main__":
    unittest.main()
