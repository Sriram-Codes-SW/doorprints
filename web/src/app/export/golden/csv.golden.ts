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
 * Golden file: the five CSV tables. JSON string literals, because the files begin with a UTF-8 BOM and use CRLF line endings.
 *
 * The rows, columns and headings come from export-rows.ts, the TypeScript half of the shared ExportRows
 * contract, so these files are comparable cell for cell with the Kotlin goldens in
 * android/shared/src/commonTest/.../export/ExportGolden.kt (same shape, different fixture data).
 *
 * Generated from golden/fixture.ts and checked in. The exporter tests compare their output with these
 * strings byte for byte, so any change to a format shows up as a diff here and has to be deliberate
 * (docs/11 section 5.2: "the same output for the same data and options").
 *
 * Do not edit by hand to make a test pass: change the exporter, then update this file on purpose.
 */

export const GOLDEN_HOUSES_CSV = "﻿Rank,House,Status,Score,Price,Price type,Bedrooms,Stars,Address,Street,Locality,Latitude,Longitude,Contact name,Phone,Broker,Listing link,Notes,Carpet area (sq ft),Location source,Deposit,Deposit (months),Maintenance per month,Maintenance included,Brokerage,Brokerage (months),Lock-in (months),Notice (months),Available from,My offer,Agreed price,Monthly cost,Money to move in,Cost per sq ft,Visits,Photos,Added,Last changed,Id\r\n1,Green View 2BHK,Shortlisted,4.1,32000,Rent per month,2,4,\"12, MG Road\",MG Road,Adyar,13.006000,80.257400,Ravi Kumar,'+91 98400 11111,Ravi Kumar (Adyar Homes),https://example.com/listing/1,\"Owner said \"\"no pets\"\" & <no smoking>.\nAsk about water in summer.\",1150,GPS,64000,,2500,No,,1,11,2,2026-10-15,30000,31000,34500,128000,27.0,2,1,2026-09-01 06:00,2026-09-10 08:30,11111111-1111-4111-8111-111111111111\r\n3,'=SUM(A1:A9) சென்னை flat,New,,,,,,,,,0.000000,0.000000,,,,,,,,,,,,,,,,,,,,,,0,0,2026-09-02 06:00,2026-09-02 06:00,22222222-2222-4222-8222-222222222222\r\n2,,Rejected,1.0,1250000,Sale price,3,1,,Beach Road,,13.050000,80.280000,,,Meena Iyer (Beach Road Realty),,Too noisy | too dark,1450,MAP,,,,,25000,,,,,,1200000,,,827.6,1,1,2026-09-03 06:00,2026-09-03 06:00,33333333-3333-4333-8333-333333333333\r\n";

export const GOLDEN_SCORES_CSV = "﻿House,Item key,What,Score,House id\r\nGreen View 2BHK,water,Water supply,5,11111111-1111-4111-8111-111111111111\r\nGreen View 2BHK,power,Power backup,3,11111111-1111-4111-8111-111111111111\r\nGreen View 2BHK,parking,Parking,4,11111111-1111-4111-8111-111111111111\r\nGreen View 2BHK,newItemFromNewerApp,newItemFromNewerApp,2,11111111-1111-4111-8111-111111111111\r\n,noise,Quiet (low noise),0,33333333-3333-4333-8333-333333333333\r\n";

export const GOLDEN_VISITS_CSV = "﻿House,Arrived,Left,Minutes,How recorded,Street,Latitude,Longitude,House id,Id\r\nGreen View 2BHK,2026-09-05 11:00,2026-09-05 11:25,25,Added by me,MG Road,13.006000,80.257400,11111111-1111-4111-8111-111111111111,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1\r\n,2026-09-06 13:06,2026-09-06 13:36,30,Added by me,Beach Road,13.050000,80.280000,33333333-3333-4333-8333-333333333333,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3\r\nGreen View 2BHK,2026-09-09 04:00,,,Automatic,MG Road,13.006100,80.257500,11111111-1111-4111-8111-111111111111,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2\r\n";

export const GOLDEN_PHOTOS_CSV = "﻿House,File,Added,House id,Id\r\n,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2.jpg,2026-09-03 06:10,33333333-3333-4333-8333-333333333333,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2\r\nGreen View 2BHK,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg,2026-09-05 11:05,11111111-1111-4111-8111-111111111111,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1\r\n";

export const GOLDEN_BROKERS_CSV = "﻿Name,Phone,Agency,Fee terms,Notes,Stars,Houses,Id\r\nMeena Iyer,,Beach Road Realty,,,,1,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\r\nRavi Kumar,'+91 98400 11111,Adyar Homes,\"15 days' rent, once\",Replies fast; shows keys on weekends,4,1,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\r\n";

export const GOLDEN_CRITERIA_CSV = "﻿Item key,Name,Weight,Must-have,Minimum score,Archived,Order\r\nwater,Water supply,High,Yes,4,No,0\r\npower,Power backup,Medium,No,3,No,1\r\nparking,Parking,Medium,No,3,No,2\r\nsunlight,Sunlight,Medium,No,3,No,3\r\nventilation,Ventilation,Medium,No,3,No,4\r\nnoise,Quiet (low noise),Ignore,No,3,Yes,5\r\nsecurity,Safety and security,Medium,No,3,No,6\r\nmaintenance,Building condition,Medium,No,3,No,7\r\nneighbourhood,Neighbourhood,Medium,No,3,No,8\r\ncommute,Commute,Medium,No,3,No,9\r\nc_1a2b3c4d,Pets allowed,Medium,No,3,No,10\r\n";
