/*
 * Golden file: the four CSV tables. JSON string literals, because the files begin with a UTF-8 BOM and use CRLF line endings.
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

export const GOLDEN_HOUSES_CSV = "﻿Rank,House,Status,Score,Price,Price type,Bedrooms,Stars,Address,Street,Locality,Latitude,Longitude,Contact name,Phone,Listing link,Notes,Visits,Photos,Added,Last changed,Id\r\n1,Green View 2BHK,Shortlisted,3.8,32000,Rent per month,2,4,\"12, MG Road\",MG Road,Adyar,13.006000,80.257400,Ravi Kumar,'+91 98400 11111,https://example.com/listing/1,\"Owner said \"\"no pets\"\" & <no smoking>.\nAsk about water in summer.\",2,1,2026-09-01 06:00,2026-09-10 08:30,11111111-1111-4111-8111-111111111111\r\n3,'=SUM(A1:A9) சென்னை flat,New,,,,,,,,,0.000000,0.000000,,,,,0,0,2026-09-02 06:00,2026-09-02 06:00,22222222-2222-4222-8222-222222222222\r\n2,,Rejected,0.5,1250000,Sale price,3,1,,Beach Road,,13.050000,80.280000,,,,Too noisy | too dark,1,1,2026-09-03 06:00,2026-09-03 06:00,33333333-3333-4333-8333-333333333333\r\n";

export const GOLDEN_SCORES_CSV = "﻿House,Item key,What,Score,House id\r\nGreen View 2BHK,water,Water supply,5,11111111-1111-4111-8111-111111111111\r\nGreen View 2BHK,power,Power backup,3,11111111-1111-4111-8111-111111111111\r\nGreen View 2BHK,parking,Parking,4,11111111-1111-4111-8111-111111111111\r\nGreen View 2BHK,newItemFromNewerApp,newItemFromNewerApp,2,11111111-1111-4111-8111-111111111111\r\n,noise,Quiet (low noise),0,33333333-3333-4333-8333-333333333333\r\n";

export const GOLDEN_VISITS_CSV = "﻿House,Arrived,Left,Minutes,How recorded,Street,Latitude,Longitude,House id,Id\r\nGreen View 2BHK,2026-09-05 11:00,2026-09-05 11:25,25,Added by me,MG Road,13.006000,80.257400,11111111-1111-4111-8111-111111111111,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1\r\n,2026-09-06 13:06,2026-09-06 13:36,30,Added by me,Beach Road,13.050000,80.280000,33333333-3333-4333-8333-333333333333,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3\r\nGreen View 2BHK,2026-09-09 04:00,,,Automatic,MG Road,13.006100,80.257500,11111111-1111-4111-8111-111111111111,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2\r\n";

export const GOLDEN_PHOTOS_CSV = "﻿House,File,Added,House id,Id\r\n,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2.jpg,2026-09-03 06:10,33333333-3333-4333-8333-333333333333,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2\r\nGreen View 2BHK,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg,2026-09-05 11:05,11111111-1111-4111-8111-111111111111,bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1\r\n";
