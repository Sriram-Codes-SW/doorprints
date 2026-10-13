/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.shared.export

import app.doorprints.drive.sha256HexOf
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseRoom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The safety test of kinds PR 6 (S4b-BL-210; docs/03 §18.10), written before typed extras reached the copies: a house's
 * copies (every CSV table, the Markdown, the HTML and every part of the XLSX workbook, in en, hi, ta and te) are byte for
 * byte what main made, pinned as SHA-256 here (the English text itself is [ExportGolden]'s). The fixture adds a third
 * house with cost, floor, carpet area and a room to [ExportFixture]'s two, so the hand-written house fields are in it. A
 * school's extras are added beside them; a house has no extra outside its own fields, so nothing here may change.
 */
class HouseCopyPinsTest {
    private val house3 = ExportFixture.house1.copy(
        id = "h3", label = "Lake View", status = "NEW", price = 41_000, floor = 3, areaSqft = 950,
        cost = HouseCost(deposit = 96_000, maintenance = 2_500, maintenanceIncluded = false, lockInMonths = 11, availableFrom = "2026-11-01"),
        rooms = listOf(HouseRoom("r1", "BEDROOM", "Master", sort = 0)),
        createdAt = 1_790_000_200_000, updatedAt = 1_790_000_300_000,
    )

    private fun digests(language: String): Map<String, String> {
        val options = ExportFixture.options(language = language)
        val bundle = ExportBundle.build(options, ExportFixture.houses + house3, ExportFixture.visits, ExportFixture.photos)
        val out = LinkedHashMap<String, String>()
        for (t in ExportRows.tables(bundle)) out["${t.name}.csv"] = sha(CsvWriter.write(t, bundle.options))
        out["markdown"] = sha(MarkdownWriter.write(bundle))
        out["html"] = sha(HtmlWriter.write(bundle, ExportFixture.fakePhotoSrc))
        for (part in XlsxWriter.parts(bundle)) out[part.path] = sha(part.xml)
        return out
    }

    private fun sha(text: String) = sha256HexOf(text.encodeToByteArray())

    @Test
    fun theHouseCopiesAreWhatMainMadeInEveryLanguage() {
        assertEquals(PINS, listOf("en", "hi", "ta", "te").associateWith { digests(it) })
    }

    private companion object {
        val PINS: Map<String, Map<String, String>> = mapOf(
            "en" to mapOf(
                "houses.csv" to "b5b11d32820a2e137a50a0d4d4074bd7170d5d388eed32dd5c31015835bdb115",
                "scores.csv" to "1b72a6fa10c19f1100c347b6869dc57e34dfcaa89dbbba1bfad02cbeedaf293c",
                "visits.csv" to "fb1a3b2915c8b4848b344e6a5927d228550c0fe599fb3dccc5046f42b9b8a994",
                "photos.csv" to "4d2b7e0a60c6fa94a48a2bbbe6d8d85c657dcf1ebdfe6b96ee773937a87f1a89",
                "rooms.csv" to "6bc824aab72d489550bc412151f650c3ac36ce2c01a2af24c2071b0345aa3331",
                "markdown" to "a10f65e04ced275a770f5e791fa869642d30b62a347cac68334c9ed879f50846",
                "html" to "4c538c970756328744ab40b0e878621ad152dc299c6fe4206c47cd5ffe677350",
                "[Content_Types].xml" to "d8531f95cac4c9abf91766b0aca3bd32118b58fc58d62baa530250e5f498642f",
                "_rels/.rels" to "9f96bb683e71c45171c7ae310bf6c37fb5a834bb9738497ac7084c42ac9c2d9a",
                "xl/workbook.xml" to "e8a82bd1b3488e73ee28fef2920cb3827dfa0bead39a579901e3bbc909165bec",
                "xl/_rels/workbook.xml.rels" to "1b9254f844ea53bb82cd706cec9b74d30f9892c25f344511615f1fd67ab58ce9",
                "xl/styles.xml" to "2f33cbdf527ceec5e0e384c517d292d870ed25333667b1c169685c8e93a6fc6c",
                "xl/worksheets/sheet1.xml" to "7c54dc0f649f6f29cf40441baf9877e54fc512c43e72ec0831d337bf0d84de80",
                "xl/worksheets/sheet2.xml" to "a7a6ec39d875e2c11bb3b7eaed53123fc5e3db91b3fdc5fcd9d5496d513a08c8",
                "xl/worksheets/sheet3.xml" to "4a0b01d245b5b42195cf0b7f07d983dff33541a0a96130a5903b240c1d1237e6",
                "xl/worksheets/sheet4.xml" to "00d98d1c1f633c58e2588fca7a1fc04e21a0ab211f8cee31d273e222a7909aae",
                "xl/worksheets/sheet5.xml" to "e634a8264b18b4672126bb588bdff6a7d41012e83033c255da5e064d50048ce3",
            ),
            "hi" to mapOf(
                "houses.csv" to "cd8b3c71e59d557dc272f7e0172ea7a6625adee36fc84d9ac0dd8a95a8d0f206",
                "scores.csv" to "91cf8591d5ddac5cb49d5587497d3f1c40dd02f3487f08d1e12638f4afc7a87d",
                "visits.csv" to "989b91617a6ea638ddcc3c4148e7ce59bc8b845d6cc529a3efdee74be6052a11",
                "photos.csv" to "5eb4bb61f58c4830edb476b1c97755a43f212b08c3f453f9d82d29e26c523919",
                "rooms.csv" to "141ab2a684dd10e4712b11f97bbd8ec5af5b88a183253d3d4a742338e5ff994c",
                "markdown" to "021f2fd57c6a3a246e0cca5d7dda40bf66a191de508d5eb8593b801c5b0fc3e2",
                "html" to "b222beeb67537d606f17b50f4a62ce82e44f9a4c5e8b5cbe7301cf7ae47225a4",
                "[Content_Types].xml" to "d8531f95cac4c9abf91766b0aca3bd32118b58fc58d62baa530250e5f498642f",
                "_rels/.rels" to "9f96bb683e71c45171c7ae310bf6c37fb5a834bb9738497ac7084c42ac9c2d9a",
                "xl/workbook.xml" to "dd187994fe292dd6b78962c450fee0521d26451a7b6bc817860bf80a7e503d04",
                "xl/_rels/workbook.xml.rels" to "1b9254f844ea53bb82cd706cec9b74d30f9892c25f344511615f1fd67ab58ce9",
                "xl/styles.xml" to "2f33cbdf527ceec5e0e384c517d292d870ed25333667b1c169685c8e93a6fc6c",
                "xl/worksheets/sheet1.xml" to "f05e4085cc6eefe820f2827062ce0529fa63895f3adb61cb13031ca9e1dc55f5",
                "xl/worksheets/sheet2.xml" to "066c52eed34e30d5c33697f4961023a6d9e5a74c0be780a15ea8089a5ae0c112",
                "xl/worksheets/sheet3.xml" to "ea7a41ceef7ce65a0db6cb99adf2cb60c3d9dd6a3de689b1a824fbe4caca183b",
                "xl/worksheets/sheet4.xml" to "87882ef905f0c003e93de931c5f77d3e20106cd2a1aeaf2e6f65523aa3ec1655",
                "xl/worksheets/sheet5.xml" to "a803746aa8f5b577daf53945496f0c14a9f202bebebb3d5525aee2a0f0068792",
            ),
            "ta" to mapOf(
                "houses.csv" to "e251da23ba65c64df6b13b147df0b3083331900ea6a154a5bb2f9a9e391de51a",
                "scores.csv" to "5cb2edd8a6dcb3c2e16690a24c6e1cf814cffe598383a8c92af85e20ec9a9d57",
                "visits.csv" to "e97e163678597d6ddcdfcf8df5c801fe2676355a79aa9332af7506904a274b2a",
                "photos.csv" to "5295dd2e6a2bb99cb3c82e334c6bd74eadeb236d52dbbced9605ad5c96244ebc",
                "rooms.csv" to "c335c1084c3397d9b1cf9138598c384cab20942458a924e2e968b270dd88d49f",
                "markdown" to "0b3c379ff67fef099a92b9d4b395358a1e665781f49a3647efc2ccfb81e6d1e6",
                "html" to "2f6207cb5170d4e870b789385fdc5fc0b55c390b9db2d5f00e5a9c836285e4d5",
                "[Content_Types].xml" to "d8531f95cac4c9abf91766b0aca3bd32118b58fc58d62baa530250e5f498642f",
                "_rels/.rels" to "9f96bb683e71c45171c7ae310bf6c37fb5a834bb9738497ac7084c42ac9c2d9a",
                "xl/workbook.xml" to "e16d5926767d9eb6bf85c9ceb5dd2c0dfa8ecb8716b069ea4b2db8d1e9cbafc4",
                "xl/_rels/workbook.xml.rels" to "1b9254f844ea53bb82cd706cec9b74d30f9892c25f344511615f1fd67ab58ce9",
                "xl/styles.xml" to "2f33cbdf527ceec5e0e384c517d292d870ed25333667b1c169685c8e93a6fc6c",
                "xl/worksheets/sheet1.xml" to "656ca0075758d3e95e30541a85753cfc10ed42a0b3881b282351619ed9f5cfcd",
                "xl/worksheets/sheet2.xml" to "d32cf4dcbb52d858a655b9edc7cf06466a6bc380d8396dd17d052c9f78dd5e6d",
                "xl/worksheets/sheet3.xml" to "9bf346465b57f849deb95b96c99a4a409b969841783f6ac12ebcafba6ef54acd",
                "xl/worksheets/sheet4.xml" to "b95dbbbcf65eb924c831183534927d259c18d4de28218c2941b94977d27d1a02",
                "xl/worksheets/sheet5.xml" to "9a55b1a9e79ced4bd9660df8787ba978892e36b14b0e1e56790a1e033db36450",
            ),
            "te" to mapOf(
                "houses.csv" to "a4c78e1ddfd062c35e6588cc028a0af422e75c76da86297558f799c8376c755d",
                "scores.csv" to "582235230d2d12c4089fb278b5dd8ac33bf5894c3543de1bafbc4d31b92cefe4",
                "visits.csv" to "c05694d4364dcbc6273f76d7822e5f613b29f9bd5170af33e5e6580a887b4d05",
                "photos.csv" to "1971e6419fd0f4865e9f124cdd6ce89fe96b975e92a149687c3d4f3f4e6f7569",
                "rooms.csv" to "183b1e534b4f36deb37d33ecddab5c04cfa212dcd01ed135b0109b67991ff977",
                "markdown" to "3780fccb43513cfd0121a36f0c89240926234bcfeaa63d4924cf8e993c7d6623",
                "html" to "50b50012e887a4c0f8d86239022702a305546160ca3214532246f18c9b7789f3",
                "[Content_Types].xml" to "d8531f95cac4c9abf91766b0aca3bd32118b58fc58d62baa530250e5f498642f",
                "_rels/.rels" to "9f96bb683e71c45171c7ae310bf6c37fb5a834bb9738497ac7084c42ac9c2d9a",
                "xl/workbook.xml" to "05fb46a2bb62cd9ba42cdb235759fb33392aa5428f539db28b7fb56736489f81",
                "xl/_rels/workbook.xml.rels" to "1b9254f844ea53bb82cd706cec9b74d30f9892c25f344511615f1fd67ab58ce9",
                "xl/styles.xml" to "2f33cbdf527ceec5e0e384c517d292d870ed25333667b1c169685c8e93a6fc6c",
                "xl/worksheets/sheet1.xml" to "413acf4263737be9c4ec08fe6c318da4e383e8331a01f7de173ef8917214ecc2",
                "xl/worksheets/sheet2.xml" to "acfafa62bc2b169464d2850e4c37398bd96b1eadf193b12ea4a5f3b20fc561dd",
                "xl/worksheets/sheet3.xml" to "f2685b1b22541b0bab37b005b2a5ffb012c81bf709a41a6f7a55bdb235b653a2",
                "xl/worksheets/sheet4.xml" to "b898f1a3ada67d3c2ee0067dff64675bc9fa4139928a8c47e23e505aceba098a",
                "xl/worksheets/sheet5.xml" to "2feb1cc60ae9e8a31123568f8da46bfbe178297639c1ddb29d52f9966a777f75",
            ),
        )
    }
}
