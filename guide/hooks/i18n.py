# Copyright 2026 Sriram (Sriram-Codes-SW)
#
# This file is part of Doorprints.
#
# Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
# Public License as published by the Free Software Foundation, version 3 of the License.
#
# Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
# warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
# the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""The user guide in Hindi, Tamil and Telugu (docs/10 S4b-BL-60), with MkDocs' own hooks and Material's language
selector: no plugin, nothing new to install.

- The English build (mkdocs.yml) builds each translation after itself, from mkdocs.<lang>.yml into <site>/<lang>/,
  so `mkdocs build --strict` (pages.yml, tools/check.sh) builds and checks all four; a warning in a translation fails
  the build as one in English does.
- A translation's pages are in i18n/<lang>/. Its images and stylesheet are the English ones from docs/, except the
  Android screens the screenshot tests already take in that language (android/app/src/test/screenshots, light
  theme), which replace their English picture. Nothing is copied into the repository.
- Each translated page starts with a note that the translation is under review (extra.under_review), as the apps'
  Hindi, Tamil and Telugu strings ship.
"""

from __future__ import annotations

import logging
import os

from mkdocs.commands.build import build
from mkdocs.config import load_config
from mkdocs.structure.files import File, Files

log = logging.getLogger("mkdocs.hooks.i18n")

GUIDE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENGLISH_DOCS = os.path.join(GUIDE, "docs")
SCREENSHOTS = os.path.join(GUIDE, os.pardir, "android", "app", "src", "test", "screenshots")
LANGUAGES = ("hi", "ta", "te")

# The guide's Android pictures and the screenshot test that takes the same screen in every language
# (ScreensScreenshotTest, <name>_<lang>_light.png). The map has none (MapLibre does not run in that test), and the
# house form only in Hindi (S4b-BL-77; the edit form's top band); a language without the shot keeps the English
# picture.
ANDROID_SHOTS = {
    "images/android-assistant.png": "assistant",
    "images/android-compare.png": "compare",
    "images/android-export.png": "export",
    "images/android-house-edit.png": "house_edit_top",
    "images/android-house-new.png": "house_new",
    "images/android-houses.png": "houses",
    "images/android-import.png": "import",
    "images/android-settings.png": "settings",
}


def _language(config) -> str:
    return config.theme["language"]


def on_files(files: Files, config) -> Files:
    lang = _language(config)
    if lang == "en":
        return files
    for f in _english_assets(config):
        if files.get_file_from_path(f.src_uri) is None:
            files.append(f)
    return files


def _english_assets(config):
    """Every file of the English docs that is not a page, in this language's shot where there is one."""
    for root, _, names in os.walk(ENGLISH_DOCS):
        for name in names:
            path = os.path.join(root, name)
            uri = os.path.relpath(path, ENGLISH_DOCS).replace(os.sep, "/")
            if uri.endswith(".md"):
                continue
            shot = ANDROID_SHOTS.get(uri)
            local = shot and os.path.join(SCREENSHOTS, f"{shot}_{_language(config)}_light.png")
            yield File.generated(config, uri, abs_src_path=local if local and os.path.isfile(local) else path)


def on_page_markdown(markdown: str, page, config, files) -> str:
    note = config.extra.get("under_review")
    if _language(config) == "en" or not note:
        return markdown
    return f'!!! note "{note["title"]}"\n    {note["text"]}\n\n{markdown}'


def on_post_build(config) -> None:
    if _language(config) != "en":
        return
    for lang in LANGUAGES:
        site_dir = os.path.join(config.site_dir, lang)
        log.info("Building the %s guide to %s", lang, site_dir)
        build(load_config(os.path.join(GUIDE, f"mkdocs.{lang}.yml"), site_dir=site_dir, strict=config.strict))
