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

import { describe, expect, it } from "vitest";
import vectorsJson from "../../../../../docs/schemas/delete-policy-vectors.json";
import {
  ACTIONS,
  confirmEnabled,
  decide,
  GateRules,
  grantCheck,
} from "./delete-policy";
import type {
  DeletionAction,
  DeletionContext,
  Factor,
  LockState,
  Requirements,
} from "./delete-policy";

/** `docs/schemas/delete-policy-vectors.json`: the same table as Kotlin's `DeletionPolicyVectorsTest` (S4b-BL-127). */
type Row = Record<string, any>;
const v = vectorsJson as unknown as {
  format: string;
  decisions: Row[];
  confirm: Row[];
  grants: Row[];
  gate: Row[];
};

describe("delete-policy vectors", () => {
  it("has the format and every action", () => {
    expect(v.format).toBe("doorprints-delete-policy-vectors/1");
    expect(new Set(v.decisions.map((r) => r["action"]))).toEqual(
      new Set(Object.keys(ACTIONS)),
    );
  });

  for (const row of vectors("decisions")) {
    it(`decision: ${row["name"]}`, () => {
      const got = decide(
        row["action"] as DeletionAction,
        row["context"] as DeletionContext,
      );
      const want = row["result"] as Row;
      if (want["outcome"] === "REFUSED") {
        expect(got).toEqual({ outcome: "REFUSED", reason: want["reason"] });
      } else {
        const { outcome, ...requirements } = want;
        expect(outcome).toBe("ALLOWED");
        expect(got).toEqual({ outcome: "ALLOWED", requirements });
      }
    });
  }

  for (const row of vectors("confirm")) {
    it(`confirm: ${row["name"]}`, () => {
      expect(
        confirmEnabled(
          { tickBox: row["tickBox"], delaySeconds: row["delaySeconds"] },
          row["ticked"],
          row["elapsedMs"],
        ),
      ).toBe(row["enabled"]);
    });
  }

  for (const row of vectors("grants")) {
    it(`grant: ${row["name"]}`, () => {
      const r: Pick<Requirements, "factor" | "authValidMs"> = {
        factor: row["factor"] as Factor,
        authValidMs: row["authValidMs"],
      };
      expect(grantCheck(r, row["grantedAtMs"], row["startedAtMs"])).toBe(
        row["result"],
      );
    });
  }

  for (const row of vectors("gate")) {
    it(`gate: ${row["name"]}`, () => {
      if (row["op"] === "CONNECT") {
        expect(GateRules.connect(row["platform"], row["lockEnabled"])).toBe(
          row["result"],
        );
      } else {
        expect(GateRules.run(row["platform"], row["lock"] as LockState)).toBe(
          row["result"],
        );
        expect(
          GateRules.dropsKeys(row["platform"], row["lock"] as LockState),
        ).toBe(row["dropsKeys"]);
      }
    });
  }
});

function vectors(key: "decisions" | "confirm" | "grants" | "gate"): Row[] {
  return v[key];
}
