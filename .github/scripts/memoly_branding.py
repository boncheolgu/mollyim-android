"""Rebrand resource text without changing resource IDs, links or upstream notices."""
import re
from pathlib import Path


UPSTREAM_NOTICES = {
    "HelpFragment__copyright_signal_messenger",
    "preferences__donate_to_signal",
    "DonateMegaphone_molly_is_free_software",
    "DonateMegaphone_we_maintain_molly_with_your_support_consider_donating_at_open_collective",
    "RegistrationActivity_contact_signal_support",
}

BRAND = re.compile(r"(?<![A-Za-z0-9_./@-])molly(?![A-Za-z0-9_]|\.im\b)", re.I)
LINK = re.compile(r"(?:https?://|mailto:)[^\s<>\"']+|[\w.+-]+@[\w.-]+", re.I)
ELEMENT = re.compile(r"(<(string|plurals|string-array)\b[^>]*>)(.*?)(</\2>)", re.S)
NAME = re.compile(r'\bname="([^"]+)"')
XML_TOKEN = re.compile(r"<!--.*?-->|<!\[CDATA\[.*?\]\]>|<[^>]+>|[^<]+", re.S)
PROJECT_LINKS = {
    "website_url": "https://github.com/boncheolgu/mollyim-android",
    "install_url": "https://github.com/boncheolgu/mollyim-android/releases",
}


def replace_text(text):
    result = []
    start = 0
    for link in LINK.finditer(text):
        result.append(BRAND.sub("Memoly", text[start:link.start()]))
        result.append(link.group())
        start = link.end()
    result.append(BRAND.sub("Memoly", text[start:]))
    branded = re.sub(r"몰리(?!소켓)", "Memoly", "".join(result))
    for old, new in (("Memoly이", "Memoly가"), ("Memoly은", "Memoly는"), ("Memoly을", "Memoly를"), ("Memoly으로", "Memoly로")):
        branded = branded.replace(old, new)
    return branded


def rebrand_resources(text):
    def element(match):
        opening, _, body, closing = match.groups()
        name = NAME.search(opening)
        if not name or name[1] in UPSTREAM_NOTICES:
            return match[0]
        if name[1] in PROJECT_LINKS:
            return opening + PROJECT_LINKS[name[1]] + closing
        if name[1] == "HelpSettingsFragment__molly_im_website":
            body = re.sub(r"molly\.im", "Memoly", body, flags=re.I)

        def token(part):
            value = part[0]
            if value.startswith("<![CDATA["):
                return "<![CDATA[" + replace_text(value[9:-3]) + "]]>"
            if value.startswith("<"):
                return value
            return replace_text(value)

        return opening + XML_TOKEN.sub(token, body) + closing

    return ELEMENT.sub(element, text)


def resource_files(root):
    return sorted(p for p in root.glob("**/src/*/res/values*/strings*.xml") if "build" not in p.parts)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    changed = 0
    for path in resource_files(root):
        old = path.read_text()
        new = rebrand_resources(old)
        if new != old:
            path.write_text(new)
            changed += 1
    print(f"Updated app branding in {changed} resource files.")
