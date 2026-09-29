package app.doorprints.ui

/**
 * An excerpt of the OpenFreeMap Liberty style (https://tiles.openfreemap.org/styles/liberty, fetched 2026-09-29): its
 * tile source and the layers India's boundary rules touch or place against, unchanged and in Liberty's order
 * (background, water, the three boundary line layers, three place label layers). The web's twin is
 * web/src/app/shared/testing/liberty-style.fixture.ts. For JsonStyleOpsTest and IndiaViewCheckTest.
 */
internal val LIBERTY_EXCERPT = """
{
 "version": 8,
 "name": null,
 "sources": {
  "openmaptiles": {
   "type": "vector",
   "url": "https://tiles.openfreemap.org/planet"
  }
 },
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
  }
 ]
}
""".trimIndent()
