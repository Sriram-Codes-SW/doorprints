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

/*
 * Golden file: what a house copy and a house search are on main before kinds PR 6 (S4b-BL-210, the safety test written
 * first, from main at d425cf9d): the SHA-256 of every table of the CSV copy, the Markdown, the HTML (photos embedded) and
 * the XLSX workbook of golden/fixture.ts in en, hi, ta and te; the Houses sheet of the workbook in English (the XLSX had
 * no golden of its own); and the list search's text of each fixture house (its broker's words, one area note, and in
 * hi, ta and te the floor in that language). Typed extras in search and copies leave every value here unchanged for a
 * house (house-pins.spec.ts). Do not edit by hand to make a test pass.
 */

export const HOUSE_COPY_SHA256: Readonly<Record<string, Readonly<Record<string, string>>>> = {
  "en": {
    "houses.csv": "c5433dd07e267de5ad178562ea45623e7660d25ff151192b4c0f5c9aa662ffc8",
    "scores.csv": "e994ed14ab5e0c4f36d81b902f9c7500dcb8707ba316cab777c08c1dffd4dd75",
    "visits.csv": "3f39df490ed2a385291981c2cea3490e3c0a7ce1dcf665f0190b9e5683b27080",
    "photos.csv": "ed566a9142ff29824f727aa16bc1766e3466533d8d1723a7fec5a0792af84579",
    "brokers.csv": "579b88b28711b90a2f636a1a5332045f59f4b68b56ca0c082314faed72f73693",
    "rooms.csv": "8076d926b2dc581772d2825b86208bd4a61d8a8cb98a4ce808e381816202fd1f",
    "criteria.csv": "6c85306fbefbf1f5338d35a6de0e5d0730ee8f89473987aa4c1bde7645c5f3df",
    "answers.csv": "abcb1fe06f889a029413246db60e12cb6b8aee9879adebb91cd0a5e09df6ab73",
    "viewings.csv": "57ac99de4c6ed2df36fb60bd66ffffaaafa6fa09ea94c234dc482813098f13b6",
    "markdown": "d076121f1dec072d7919539381a4b7aa0fbfccb1421c02cf4f66c13406183847",
    "html": "5cf43115c2b005ae6f642269a75798043dd2864fd20f21341b891959645c7988",
    "xlsx": "62246a9900e72101e27ab51e6f2d563a60aff45c638a2269c12fba18b23d4edc"
  },
  "hi": {
    "houses.csv": "7f8423ceec61593f8771ed1cbc962cfd96d87d8190cdfaeb6a6485f8e52261a4",
    "scores.csv": "c94bf66167ce47f44ac8bdb1ba61a9ca2919f706ff34a078e2087146a6851cdb",
    "visits.csv": "af624cb85e8039b01e54216d3c19dcc5e139d4c123d72a6c515183a31947ce75",
    "photos.csv": "0102d62f36ab844fa666f4eb87099a67876989573ab017c43ba39e85beacb2fd",
    "brokers.csv": "26bd0bfaa4b03b90f97e9fb78cfe4f063728ab5c3cc051a07355159a86f06d67",
    "rooms.csv": "8f9146ebe8de37eac90a2bde21dc6c0b9ab35a2e5ebfb66e2778d49b39976677",
    "criteria.csv": "3c3890e77ec16a6abcd0eaca3accb4ece91659a694a4802ae0ec614917902e31",
    "answers.csv": "f9d47008726c956d6f1969ab12b4098ff52c3f65bff0a1ed46eff587ab21ef94",
    "viewings.csv": "cbc2beb30d0353dd96be5c834774c452efcdfe075be3efd465a7ce0bc60abb31",
    "markdown": "be8d6991eda25565c618364b9d20612114423e056c2544d7db4850093813c72f",
    "html": "134816fd8ed7f72c8cda472bf05cf7b4b5de2a6e47a554177b9b6222363c33ed",
    "xlsx": "8c5543ce1f3c3886221cb9e65224bb2f2f4ed8c11eba13535fd8fe67849cef03"
  },
  "ta": {
    "houses.csv": "1a0ccd8bbded232fcf1c614449119e498c0dad8f8758a5b806bac1c5ba587077",
    "scores.csv": "ae857733e13493f9e0d8544d2dc9165a2d021949eeeec826165f15cbaf03a872",
    "visits.csv": "ec2e2cc802cac53cc3970429a2a0db851e20f63b2ff5185292f916e228cb0cda",
    "photos.csv": "6250ff0b2ce9433decf73c9f87d77d78e24438f4aafd278940ffe75fec1861f0",
    "brokers.csv": "8c06299ad32f80e21e3a026adfa6a1f7ac902186d40e0fcb024365180e9e4942",
    "rooms.csv": "55dea3cae05ba7dfe0e33fbf04d9bb82e5e72af43576a7f43edf125a8f73c86b",
    "criteria.csv": "6216fbec29f8cde6aac8d10352d58a4ec8a580dc79a2d596cdeec114cd95bb2e",
    "answers.csv": "64df5df648a81f9b577901cc6ad7fda93fb6705e6c7dbe2cee79543edae35193",
    "viewings.csv": "f47403650448db977bc4e1073971db36ae0fdb3f49bc7f510285f41025623ed3",
    "markdown": "aa4ce6910f33a18dc3195495a475420aa9731a76ab9b1fe1310a54599cdace50",
    "html": "f1116b1e40e4f23fb27213a70907f6d798637b4557adbd971051ae85b97bf626",
    "xlsx": "439a6b32e4c8c8cb28eeda9a9411421f72e4849b6515814ff7af5635cbd72672"
  },
  "te": {
    "houses.csv": "6b2c8dec43224517dc4542295f92b68ed89cef29bab95ee7bb7a2069f8aa7c1e",
    "scores.csv": "426204774759460b38e551067b5aff1a3f3c7cddb4c1f2d2eb020f98987c388d",
    "visits.csv": "b427e14e7a93ca3343bb1dad237946bf27f8940ed4c44623c84db556574b79da",
    "photos.csv": "a26dd81f8cd4b4df442fe27740c5c3224ec218d8f536d1622e888e0b32a531bb",
    "brokers.csv": "94d777fc9297f778d04d50522d095f5b5b9636e65e00f19c5096563f6bfc0d9f",
    "rooms.csv": "ff3037e2d79e875bd43bf84a997617b737f0affec0c96940e6bccfd3489afe70",
    "criteria.csv": "ee7460da7626964abd3e8d733a2a858902190c2487157609f31894f087910e76",
    "answers.csv": "b006432ce2c21afa091ec293f26f65db8eef5f832116cfb7351eed30a4b5f985",
    "viewings.csv": "e9e3d27e8a2eada8aa69a1da476c000f13949368236be751a72bdcaae75569a2",
    "markdown": "2598321a67264672246acb07102e2d24c83220bdd22b98d82678036c9a756c2b",
    "html": "b80bc87570ecf6a411fccaf7432b83fa46bba4e1e0dfe4cc47ea380903ed2a6d",
    "xlsx": "38733cd03bf009aa89ccc0a54df4bba2e11b1f4d359357f114cec6f1d6ba0360"
  }
};

export const HOUSE_SEARCH_TEXT: Readonly<Record<string, readonly string[]>> = {
  "en": [
    "green view 2bhk 12, mg road mg road adyar owner said \"no pets\" & <no smoking>.\nask about water in summer. ravi kumar ravi kumar adyar homes 15 days' rent, once master bedroom damp patch near the window kitchen how much is the maintenance per month, and what does it cover? ₹2,500 a month; it covers the lift, water and security is the terrace open to tenants? keys handed over by ravi. electricity meter reads 4521. rental agreement signed and registered police verification done a note floor 3",
    "=sum(a1:a9) சென்னை flat a note",
    "beach road too noisy | too dark meena iyer beach road realty a note ground floor 0",
    "deleted house a note"
  ],
  "hi": [
    "green view 2bhk 12, mg road mg road adyar owner said \"no pets\" & <no smoking>.\nask about water in summer. ravi kumar ravi kumar adyar homes 15 days' rent, once master bedroom damp patch near the window kitchen how much is the maintenance per month, and what does it cover? ₹2,500 a month; it covers the lift, water and security is the terrace open to tenants? keys handed over by ravi. electricity meter reads 4521. rental agreement signed and registered police verification done a note floor 3 मंज़िल 3",
    "=sum(a1:a9) சென்னை flat a note",
    "beach road too noisy | too dark meena iyer beach road realty a note ground floor 0 भूतल",
    "deleted house a note"
  ],
  "ta": [
    "green view 2bhk 12, mg road mg road adyar owner said \"no pets\" & <no smoking>.\nask about water in summer. ravi kumar ravi kumar adyar homes 15 days' rent, once master bedroom damp patch near the window kitchen how much is the maintenance per month, and what does it cover? ₹2,500 a month; it covers the lift, water and security is the terrace open to tenants? keys handed over by ravi. electricity meter reads 4521. rental agreement signed and registered police verification done a note floor 3 தளம் 3",
    "=sum(a1:a9) சென்னை flat a note",
    "beach road too noisy | too dark meena iyer beach road realty a note ground floor 0 தரைத்தளம்",
    "deleted house a note"
  ],
  "te": [
    "green view 2bhk 12, mg road mg road adyar owner said \"no pets\" & <no smoking>.\nask about water in summer. ravi kumar ravi kumar adyar homes 15 days' rent, once master bedroom damp patch near the window kitchen how much is the maintenance per month, and what does it cover? ₹2,500 a month; it covers the lift, water and security is the terrace open to tenants? keys handed over by ravi. electricity meter reads 4521. rental agreement signed and registered police verification done a note floor 3 అంతస్తు 3",
    "=sum(a1:a9) சென்னை flat a note",
    "beach road too noisy | too dark meena iyer beach road realty a note ground floor 0 నేల అంతస్తు",
    "deleted house a note"
  ]
};

/** `xl/worksheets/sheet1.xml` of the English workbook, the Houses sheet. */
export const GOLDEN_XLSX_HOUSES_SHEET =
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>PK\u0003\u0004\u0014\u0000\u0000\b\u0000\u0000�Q6]\u000f��4�\u0002\u0000\u0000�\u0002\u0000\u0000\u000f\u0000\u0000\u0000xl/workbook.xml<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"houses\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"scores\" sheetId=\"2\" r:id=\"rId2\"/><sheet name=\"visits\" sheetId=\"3\" r:id=\"rId3\"/><sheet name=\"photos\" sheetId=\"4\" r:id=\"rId4\"/><sheet name=\"brokers\" sheetId=\"5\" r:id=\"rId5\"/><sheet name=\"rooms\" sheetId=\"6\" r:id=\"rId6\"/><sheet name=\"criteria\" sheetId=\"7\" r:id=\"rId7\"/><sheet name=\"answers\" sheetId=\"8\" r:id=\"rId8\"/><sheet name=\"viewings\" sheetId=\"9\" r:id=\"rId9\"/></sheets></workbook>PK\u0003\u0004\u0014\u0000\u0000\b\u0000\u0000�Q6]IQxr�\u0005\u0000\u0000�\u0005\u0000\u0000\u001a\u0000\u0000\u0000xl/_rels/workbook.xml.rels<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet3.xml\"/><Relationship Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet4.xml\"/><Relationship Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet5.xml\"/><Relationship Id=\"rId6\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet6.xml\"/><Relationship Id=\"rId7\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet7.xml\"/><Relationship Id=\"rId8\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet8.xml\"/><Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet9.xml\"/></Relationships>PK\u0003\u0004\u0014\u0000\u0000\b\u0000\u0000�Q6]\"\\��m\u0004\u0000\u0000m\u0004\u0000\u0000\r\u0000\u0000\u0000xl/styles.xml<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"&quot;₹&quot;#,##,##0\"/><numFmt numFmtId=\"165\" formatCode=\"yyyy\\-mm\\-dd\"/></numFmts><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>PK\u0003\u0004\u0014\u0000\u0000\b\u0000\u0000�Q6]\u0017���\u0001\u001c\u0000\u0000\u0001\u001c\u0000\u0000\u0018\u0000\u0000\u0000xl/worksheets/sheet1.xml<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:AO4\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><sheetData><row r=\"1\"><c r=\"A1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Rank</t></is></c><c r=\"B1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">House</t></is></c><c r=\"C1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Status</t></is></c><c r=\"D1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Score</t></is></c><c r=\"E1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Price</t></is></c><c r=\"F1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Price type</t></is></c><c r=\"G1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Bedrooms</t></is></c><c r=\"H1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Stars</t></is></c><c r=\"I1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Address</t></is></c><c r=\"J1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Street</t></is></c><c r=\"K1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Locality</t></is></c><c r=\"L1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Latitude</t></is></c><c r=\"M1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Longitude</t></is></c><c r=\"N1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Contact name</t></is></c><c r=\"O1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Phone</t></is></c><c r=\"P1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Broker</t></is></c><c r=\"Q1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Listing link</t></is></c><c r=\"R1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Notes</t></is></c><c r=\"S1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Carpet area (sq ft)</t></is></c><c r=\"T1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Location source</t></is></c><c r=\"U1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Deposit</t></is></c><c r=\"V1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Deposit (months)</t></is></c><c r=\"W1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Maintenance per month</t></is></c><c r=\"X1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Maintenance included</t></is></c><c r=\"Y1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Brokerage</t></is></c><c r=\"Z1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Brokerage (months)</t></is></c><c r=\"AA1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Lock-in (months)</t></is></c><c r=\"AB1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Notice (months)</t></is></c><c r=\"AC1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Available from</t></is></c><c r=\"AD1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">My offer</t></is></c><c r=\"AE1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Agreed price</t></is></c><c r=\"AF1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Monthly cost</t></is></c><c r=\"AG1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Money to move in</t></is></c><c r=\"AH1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Cost per sq ft</t></is></c><c r=\"AI1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Rooms</t></is></c><c r=\"AJ1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Floor</t></is></c><c r=\"AK1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Visits</t></is></c><c r=\"AL1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Photos</t></is></c><c r=\"AM1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Added</t></is></c><c r=\"AN1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Last changed</t></is></c><c r=\"AO1\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Id</t></is></c></row><row r=\"2\"><c r=\"A2\"><v>1</v></c><c r=\"B2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Green View 2BHK</t></is></c><c r=\"C2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Taken</t></is></c><c r=\"D2\"><v>4.0857142857</v></c><c r=\"E2\" s=\"1\"><v>32000</v></c><c r=\"F2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Rent per month</t></is></c><c r=\"G2\"><v>2</v></c><c r=\"H2\"><v>4</v></c><c r=\"I2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">12, MG Road</t></is></c><c r=\"J2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">MG Road</t></is></c><c r=\"K2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Adyar</t></is></c><c r=\"L2\"><v>13.006</v></c><c r=\"M2\"><v>80.2574</v></c><c r=\"N2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Ravi Kumar</t></is></c><c r=\"O2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">+91 98400 11111</t></is></c><c r=\"P2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Ravi Kumar (Adyar Homes)</t></is></c><c r=\"Q2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">https://example.com/listing/1</t></is></c><c r=\"R2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Owner said &quot;no pets&quot; &amp; &lt;no smoking&gt;.\nAsk about water in summer.</t></is></c><c r=\"S2\"><v>1150</v></c><c r=\"T2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">GPS</t></is></c><c r=\"U2\" s=\"1\"><v>64000</v></c><c r=\"W2\" s=\"1\"><v>2500</v></c><c r=\"X2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">No</t></is></c><c r=\"Z2\"><v>1</v></c><c r=\"AA2\"><v>11</v></c><c r=\"AB2\"><v>2</v></c><c r=\"AC2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">2026-10-15</t></is></c><c r=\"AD2\" s=\"1\"><v>30000</v></c><c r=\"AE2\" s=\"1\"><v>31000</v></c><c r=\"AF2\" s=\"1\"><v>34500</v></c><c r=\"AG2\" s=\"1\"><v>128000</v></c><c r=\"AH2\"><v>27</v></c><c r=\"AI2\"><v>2</v></c><c r=\"AJ2\"><v>3</v></c><c r=\"AK2\"><v>2</v></c><c r=\"AL2\"><v>1</v></c><c r=\"AM2\" s=\"2\"><v>46266.25</v></c><c r=\"AN2\" s=\"2\"><v>46275.3541666667</v></c><c r=\"AO2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">11111111-1111-4111-8111-111111111111</t></is></c></row><row r=\"3\"><c r=\"A3\"><v>3</v></c><c r=\"B3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">=SUM(A1:A9) சென்னை flat</t></is></c><c r=\"C3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">New</t></is></c><c r=\"L3\"><v>0</v></c><c r=\"M3\"><v>0</v></c><c r=\"AK3\"><v>0</v></c><c r=\"AL3\"><v>0</v></c><c r=\"AM3\" s=\"2\"><v>46267.25</v></c><c r=\"AN3\" s=\"2\"><v>46267.25</v></c><c r=\"AO3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">22222222-2222-4222-8222-222222222222</t></is></c></row><row r=\"4\"><c r=\"A4\"><v>2</v></c><c r=\"C4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Rejected</t></is></c><c r=\"D4\"><v>1</v></c><c r=\"E4\" s=\"1\"><v>1250000</v></c><c r=\"F4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Sale price</t></is></c><c r=\"G4\"><v>3</v></c><c r=\"H4\"><v>1</v></c><c r=\"J4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Beach Road</t></is></c><c r=\"L4\"><v>13.05</v></c><c r=\"M4\"><v>80.28</v></c><c r=\"P4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Meena Iyer (Beach Road Realty)</t></is></c><c r=\"R4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Too noisy | too dark</t></is></c><c r=\"S4\"><v>1450</v></c><c r=\"T4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">MAP</t></is></c><c r=\"Y4\" s=\"1\"><v>25000</v></c><c r=\"AE4\" s=\"1\"><v>1200000</v></c><c r=\"AH4\"><v>827.6</v></c><c r=\"AJ4\"><v>0</v></c><c r=\"AK4\"><v>1</v></c><c r=\"AL4\"><v>1</v></c><c r=\"AM4\" s=\"2\"><v>46268.25</v></c><c r=\"AN4\" s=\"2\"><v>46268.25</v></c><c r=\"AO4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">33333333-3333-4333-8333-333333333333</t></is></c></row></sheetData><autoFilter ref=\"A1:AO4\"/></worksheet>";
