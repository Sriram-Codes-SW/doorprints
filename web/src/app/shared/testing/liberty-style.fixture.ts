/**
 * Test fixture: the OpenFreeMap "liberty" style as both apps load it (https://tiles.openfreemap.org/styles/liberty,
 * copy of 2026-09-23 in `.claude-state/map/liberty.json`), cut down to what the India boundary rules touch and
 * the layers around them, in their original order and with their original JSON:
 *
 *  - `background`, `water`, `road_one_way_arrow` (Liberty's first symbol layer), `building` and `building-3d`
 *    (the layers below the boundaries);
 *  - `boundary_3`, `boundary_2`, `boundary_disputed` (the three layers on source-layer `boundary`);
 *  - `waterway_line_label` (the layer right above the boundaries);
 *  - every layer on source-layer `place`: `label_other` ... `label_country_1`, `label_state` among them.
 *
 * Kept as plain JSON typed `unknown` on purpose: Liberty's own values (`["linear", 1]` in `boundary_3`) are wider
 * than the style-spec TypeScript types, and a test must see the style exactly as MapLibre receives it.
 * Test-only: excluded from the app build (tsconfig.app.json) and compiled by tsconfig.spec.json.
 */
import type { StyleSpecification } from 'maplibre-gl';

const LIBERTY_EXCERPT: unknown = {
  "version": 8,
  "sources": {
    "ne2_shaded": {
      "maxzoom": 6,
      "tileSize": 256,
      "tiles": [
        "https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"
      ],
      "type": "raster"
    },
    "openmaptiles": {
      "type": "vector",
      "url": "https://tiles.openfreemap.org/planet"
    }
  },
  "sprite": "https://tiles.openfreemap.org/sprites/ofm_f384/ofm",
  "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  "layers": [
    {
      "id": "background",
      "type": "background",
      "paint": {
        "background-color": "#f8f4f0"
      }
    },
    {
      "id": "water",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "water",
      "filter": [
        "!=",
        [
          "get",
          "brunnel"
        ],
        "tunnel"
      ],
      "paint": {
        "fill-color": "rgb(158,189,255)"
      }
    },
    {
      "id": "road_one_way_arrow",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "transportation",
      "minzoom": 16,
      "filter": [
        "==",
        [
          "get",
          "oneway"
        ],
        1
      ],
      "layout": {
        "icon-image": "arrow",
        "symbol-placement": "line"
      }
    },
    {
      "id": "building",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "building",
      "minzoom": 13,
      "maxzoom": 14,
      "paint": {
        "fill-color": "hsl(35,8%,85%)",
        "fill-outline-color": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          13,
          "hsla(35,6%,79%,0.32)",
          14,
          "hsl(35,6%,79%)"
        ]
      }
    },
    {
      "id": "building-3d",
      "type": "fill-extrusion",
      "source": "openmaptiles",
      "source-layer": "building",
      "minzoom": 14,
      "paint": {
        "fill-extrusion-base": [
          "get",
          "render_min_height"
        ],
        "fill-extrusion-color": "hsl(35,8%,85%)",
        "fill-extrusion-height": [
          "get",
          "render_height"
        ],
        "fill-extrusion-opacity": 0.8
      }
    },
    {
      "id": "boundary_3",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "boundary",
      "minzoom": 5,
      "filter": [
        "all",
        [
          ">=",
          [
            "get",
            "admin_level"
          ],
          3
        ],
        [
          "<=",
          [
            "get",
            "admin_level"
          ],
          6
        ],
        [
          "!=",
          [
            "get",
            "maritime"
          ],
          1
        ],
        [
          "!=",
          [
            "get",
            "disputed"
          ],
          1
        ],
        [
          "!",
          [
            "has",
            "claimed_by"
          ]
        ]
      ],
      "paint": {
        "line-color": "hsl(0,0%,70%)",
        "line-dasharray": [
          1,
          1
        ],
        "line-width": [
          "interpolate",
          [
            "linear",
            1
          ],
          [
            "zoom"
          ],
          7,
          1,
          11,
          2
        ]
      }
    },
    {
      "id": "boundary_2",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "boundary",
      "filter": [
        "all",
        [
          "==",
          [
            "get",
            "admin_level"
          ],
          2
        ],
        [
          "!=",
          [
            "get",
            "maritime"
          ],
          1
        ],
        [
          "!=",
          [
            "get",
            "disputed"
          ],
          1
        ],
        [
          "!",
          [
            "has",
            "claimed_by"
          ]
        ]
      ],
      "layout": {
        "line-cap": "round",
        "line-join": "round"
      },
      "paint": {
        "line-color": "hsl(248,1%,41%)",
        "line-opacity": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          0,
          0.4,
          4,
          1
        ],
        "line-width": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          3,
          1,
          5,
          1.2,
          12,
          3
        ]
      }
    },
    {
      "id": "boundary_disputed",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "boundary",
      "filter": [
        "all",
        [
          "!=",
          [
            "get",
            "maritime"
          ],
          1
        ],
        [
          "==",
          [
            "get",
            "disputed"
          ],
          1
        ]
      ],
      "paint": {
        "line-color": "hsl(248,1%,41%)",
        "line-dasharray": [
          1,
          2
        ],
        "line-width": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          3,
          1,
          5,
          1.2,
          12,
          3
        ]
      }
    },
    {
      "id": "waterway_line_label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "waterway",
      "minzoom": 10,
      "filter": [
        "match",
        [
          "geometry-type"
        ],
        [
          "LineString",
          "MultiLineString"
        ],
        true,
        false
      ],
      "layout": {
        "symbol-placement": "line",
        "symbol-spacing": 350,
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            " ",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Italic"
        ],
        "text-letter-spacing": 0.2,
        "text-max-width": 5,
        "text-size": 14
      },
      "paint": {
        "text-color": "#74aee9",
        "text-halo-color": "rgba(255,255,255,0.7)",
        "text-halo-width": 1.5
      }
    },
    {
      "id": "label_other",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 8,
      "filter": [
        "match",
        [
          "get",
          "class"
        ],
        [
          "city",
          "continent",
          "country",
          "state",
          "town",
          "village"
        ],
        false,
        true
      ],
      "layout": {
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Italic"
        ],
        "text-letter-spacing": 0.1,
        "text-max-width": 9,
        "text-size": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          8,
          9,
          12,
          10
        ],
        "text-transform": "uppercase"
      },
      "paint": {
        "text-color": "#333",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_village",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 9,
      "filter": [
        "==",
        [
          "get",
          "class"
        ],
        "village"
      ],
      "layout": {
        "icon-allow-overlap": true,
        "icon-image": [
          "step",
          [
            "zoom"
          ],
          "circle_11_black",
          10,
          ""
        ],
        "icon-optional": false,
        "icon-size": 0.2,
        "text-anchor": "bottom",
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Regular"
        ],
        "text-max-width": 8,
        "text-size": [
          "interpolate",
          [
            "exponential",
            1.2
          ],
          [
            "zoom"
          ],
          7,
          10,
          11,
          12
        ]
      },
      "paint": {
        "text-color": "#000",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_town",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 6,
      "filter": [
        "==",
        [
          "get",
          "class"
        ],
        "town"
      ],
      "layout": {
        "icon-allow-overlap": true,
        "icon-image": [
          "step",
          [
            "zoom"
          ],
          "circle_11_black",
          10,
          ""
        ],
        "icon-optional": false,
        "icon-size": 0.2,
        "text-anchor": "bottom",
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Regular"
        ],
        "text-max-width": 8,
        "text-size": [
          "interpolate",
          [
            "exponential",
            1.2
          ],
          [
            "zoom"
          ],
          7,
          12,
          11,
          14
        ]
      },
      "paint": {
        "text-color": "#000",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_state",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 5,
      "maxzoom": 8,
      "filter": [
        "==",
        [
          "get",
          "class"
        ],
        "state"
      ],
      "layout": {
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Italic"
        ],
        "text-letter-spacing": 0.2,
        "text-max-width": 9,
        "text-size": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          5,
          10,
          8,
          14
        ],
        "text-transform": "uppercase"
      },
      "paint": {
        "text-color": "#333",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_city",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 3,
      "filter": [
        "all",
        [
          "==",
          [
            "get",
            "class"
          ],
          "city"
        ],
        [
          "!=",
          [
            "get",
            "capital"
          ],
          2
        ]
      ],
      "layout": {
        "icon-allow-overlap": true,
        "icon-image": [
          "step",
          [
            "zoom"
          ],
          "circle_11_black",
          9,
          ""
        ],
        "icon-optional": false,
        "icon-size": 0.4,
        "text-anchor": "bottom",
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Regular"
        ],
        "text-max-width": 8,
        "text-offset": [
          0,
          -0.1
        ],
        "text-size": [
          "interpolate",
          [
            "exponential",
            1.2
          ],
          [
            "zoom"
          ],
          4,
          11,
          7,
          13,
          11,
          18
        ]
      },
      "paint": {
        "text-color": "#000",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_city_capital",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 3,
      "filter": [
        "all",
        [
          "==",
          [
            "get",
            "class"
          ],
          "city"
        ],
        [
          "==",
          [
            "get",
            "capital"
          ],
          2
        ]
      ],
      "layout": {
        "icon-allow-overlap": true,
        "icon-image": [
          "step",
          [
            "zoom"
          ],
          "circle_11_black",
          9,
          ""
        ],
        "icon-optional": false,
        "icon-size": 0.5,
        "text-anchor": "bottom",
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Bold"
        ],
        "text-max-width": 8,
        "text-offset": [
          0,
          -0.2
        ],
        "text-size": [
          "interpolate",
          [
            "exponential",
            1.2
          ],
          [
            "zoom"
          ],
          4,
          12,
          7,
          14,
          11,
          20
        ]
      },
      "paint": {
        "text-color": "#000",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_country_3",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "minzoom": 2,
      "maxzoom": 9,
      "filter": [
        "all",
        [
          "==",
          [
            "get",
            "class"
          ],
          "country"
        ],
        [
          ">=",
          [
            "get",
            "rank"
          ],
          3
        ]
      ],
      "layout": {
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Bold"
        ],
        "text-max-width": 6.25,
        "text-size": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          3,
          9,
          7,
          17
        ]
      },
      "paint": {
        "text-color": "#000",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_country_2",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "maxzoom": 9,
      "filter": [
        "all",
        [
          "==",
          [
            "get",
            "class"
          ],
          "country"
        ],
        [
          "==",
          [
            "get",
            "rank"
          ],
          2
        ]
      ],
      "layout": {
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Bold"
        ],
        "text-max-width": 6.25,
        "text-size": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          2,
          9,
          5,
          17
        ]
      },
      "paint": {
        "text-color": "#000",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    },
    {
      "id": "label_country_1",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "maxzoom": 9,
      "filter": [
        "all",
        [
          "==",
          [
            "get",
            "class"
          ],
          "country"
        ],
        [
          "==",
          [
            "get",
            "rank"
          ],
          1
        ]
      ],
      "layout": {
        "text-field": [
          "case",
          [
            "has",
            "name:nonlatin"
          ],
          [
            "concat",
            [
              "get",
              "name:latin"
            ],
            "\n",
            [
              "get",
              "name:nonlatin"
            ]
          ],
          [
            "coalesce",
            [
              "get",
              "name_en"
            ],
            [
              "get",
              "name"
            ]
          ]
        ],
        "text-font": [
          "Noto Sans Bold"
        ],
        "text-max-width": 6.25,
        "text-size": [
          "interpolate",
          [
            "linear"
          ],
          [
            "zoom"
          ],
          1,
          9,
          4,
          17
        ]
      },
      "paint": {
        "text-color": "#000",
        "text-halo-blur": 1,
        "text-halo-color": "#fff",
        "text-halo-width": 1
      }
    }
  ]
};

/** A fresh deep copy of the excerpt for each test, so no test can see another test's changes. */
export function libertyExcerpt(): StyleSpecification {
  return JSON.parse(JSON.stringify(LIBERTY_EXCERPT)) as StyleSpecification;
}
