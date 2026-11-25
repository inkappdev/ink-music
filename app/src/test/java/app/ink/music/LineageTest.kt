package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A song's family read from MusicBrainz's answers as they came (2026-11-24, trimmed to a few of each): Madonna's "Hung Up"
 * (its search, its relations: samples ABBA, remixed), Johnny Cash's "Hurt" (a cover: its work, the work's recordings).
 */
class LineageTest {
    // /ws/2/recording/?query=recording:"Hung Up" AND artist:"Madonna"&limit=25 (five of its 25 kept)
    private val search = """
        {"count":75,"offset":0,"recordings":[{"id":"e06c9258-f0bd-49e3-bdf9-ea2398621840","score":100,"title":"Hung Up","video":null,"artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}],"releases":[{"id":"64110d64-86a7-4079-8660-b271a1efac0d","title":"The Best"}]},
        {"id":"b2fef07d-4703-4df3-9ba2-50221beed5e6","score":100,"title":"Hung Up","length":233000,"video":null,"first-release-date":"2024-03-20","disambiguation":"part of \"ILIKEYOU!, Vol. 1\" DJ-mix","artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}],"releases":[{"id":"155a50e7-895e-48c6-984b-80e5aa406741","title":"ILIKEYOU!, Vol. 1"}]},
        {"id":"d570433a-389e-4e66-8951-ce558e43525b","score":100,"title":"Hung Up","length":329000,"video":null,"first-release-date":"2008","artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}],"releases":[{"id":"1ec9adee-b5df-4764-bffa-884f832206fa","title":"Greatest Hits"}]},
        {"id":"d63f9a2c-aea5-4b77-875e-eee7ce1fd734","score":100,"title":"Hung Up","length":336880,"video":null,"first-release-date":"2005-11-11","artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}],"releases":[{"id":"77d2b2e9-e778-4b14-96fd-80c9e2543682","title":"90er & 2000er Party"},
        {"id":"48785d7d-dfaa-420c-8a06-6174438d77c8","title":"Prom 2026"},
        {"id":"067be32d-88e9-4ef5-85dd-897af2b3f814","title":"Pop Girly Summer"},
        {"id":"a2492396-a949-454e-afed-02d86a0fc572","title":"Pop Bops 2026"},
        {"id":"7f56b522-c9a9-4ef0-861d-c4b4462d6c21","title":"Celebration"},
        {"id":"e145455a-c160-4778-ab74-33a2023563ee","title":"Celebration"},
        {"id":"0aee907c-d6ae-4255-93d3-a41d006eedf5","title":"Songs for Hot Girls"},
        {"id":"059f5130-e6d8-42d0-9600-f1eb7bd84e29","title":"Confessions on a Dance Floor (twenty years edition)"},
        {"id":"044b34c1-bcee-4b44-8000-5793d11a1f0c","title":"Pop Brunch"},
        {"id":"e03c3ec3-a087-4a66-b38b-93562ebf8389","title":"2025 HOT GIRL SUMMER HITS"},
        {"id":"e3d6043e-24b8-4c5a-b3d0-960096d615ba","title":"Clubbing Like It’s the 2000s"},
        {"id":"5965531f-c922-42c7-9d7b-2852ff5ca8ef","title":"Confessions On A Dance Floor"},
        {"id":"e9d104d6-4de0-46d6-99d2-bf07e0be28c4","title":"Celebration"},
        {"id":"846817a3-567b-4874-9973-4b94d03a4bda","title":"Celebration"},
        {"id":"1aeb1f5a-73ec-4d1b-b331-02c846786f16","title":"Celebration"},
        {"id":"4c5c47b1-2976-4939-9165-8202c38ff615","title":"Celebration"},
        {"id":"94e556f4-ac3a-4729-a33f-7b96d10ed9b0","title":"Celebration"},
        {"id":"41e4f9f4-c9f6-4a18-800d-8ec8e52a3c92","title":"Celebration"},
        {"id":"73b6e1c0-c7c7-4165-82c1-ca45cc196232","title":"Celebration"},
        {"id":"9fd09aca-e2ca-46e0-989e-470f5ce5cc96","title":"Confessions on a Dance Floor"},
        {"id":"9101319a-a27f-4020-84df-d3474722c53b","title":"Confessions on a Dance Floor"},
        {"id":"652db198-8081-4125-8a7b-7d9c201a049a","title":"Music of the Millenium"},
        {"id":"1a84365b-2bdf-4384-a0f5-b327bbddc0d7","title":"Confessions on a Dance Floor (Limited Edition Pink Vinyl)"},
        {"id":"6eb9ccca-63ad-41f5-9ab9-45d4f6e5d6a4","title":"Confessions on a Dance Floor"},
        {"id":"91520abc-aa5d-4cd1-bc0c-b38a99940341","title":"Confessions on a Dance Floor"},
        {"id":"5a943bb2-7388-4a42-bf06-b7a197fa68dd","title":"Original Album Series"},
        {"id":"26a19d01-1961-4362-a790-fa564b94ffce","title":"Celebration"},
        {"id":"18d7b623-e8ca-4afb-add0-d29a7e0fcc9a","title":"Celebration"},
        {"id":"358b077e-9452-385e-ad42-41adba6e6150","title":"Celebration"},
        {"id":"dda771d1-8b13-4b44-be84-5ac698561fd7","title":"Celebration"},
        {"id":"ff6b0565-45ae-31d4-9ff7-98cd130094ad","title":"Celebration"},
        {"id":"c2ecc909-d09d-46a7-8ca8-5a2404528f33","title":"Celebration"},
        {"id":"a9c81e70-af83-48f2-bec0-b92a107ef493","title":"Confessions on a Dance Floor"},
        {"id":"d7929b28-5812-4b8f-a99f-1800983c71fb","title":"Confessions on a Dance Floor"},
        {"id":"dad32a16-edb0-3ac7-91d4-7d28c3fab90e","title":"Confessions on a Dance Floor"},
        {"id":"0c2baed2-34ff-388d-90ea-8d6974f9713d","title":"Confessions on a Dance Floor"},
        {"id":"a54751f5-bc46-494e-9636-b5fd9fb4cf63","title":"The Complete Studio Albums (1983-2008)"},
        {"id":"8eb5bdf9-9679-4916-869e-3becfa427599","title":"Confessions on a Dance Floor"},
        {"id":"29fa921b-3d1d-4933-b011-ca7a0d926ed6","title":"Confessions on a Dance Floor"}]},
        {"id":"8be8119e-c5c7-45dd-9747-223e7867ba77","score":100,"title":"Hung Up","length":590120,"video":null,"first-release-date":"2007-01-17","disambiguation":"live, 2006-08: Wembley Arena, London, England, UK","artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}],"releases":[{"id":"10d4cf71-7dad-470a-9099-235acae0bd5e","title":"The Confessions Tour"},
        {"id":"6b6276c5-00ed-4b91-b4c7-e03bb05ebe63","title":"The Confessions Tour"},
        {"id":"8a2c9cfb-77af-4400-8193-7bd1782ebaa0","title":"The Confessions Tour"},
        {"id":"a11d81be-e075-4b8e-bb4d-89b8d757ba28","title":"The Confessions Tour"},
        {"id":"b52abbc0-16ab-422a-83de-40876cb294c7","title":"The Confessions Tour"},
        {"id":"061dfefa-84aa-4920-9798-ef07fd4ee6ca","title":"The Confessions Tour"},
        {"id":"bd0c4382-fda4-461e-998e-f3702b0e82ca","title":"The Confessions Tour"},
        {"id":"42d9cd2f-bdfa-34c7-93de-bb0a3e63dc37","title":"The Confessions Tour"}]}]}
    """

    // /ws/2/recording/d63f9a2c-...?inc=work-rels+recording-rels+artist-credits (one of its mash-ups kept)
    private val hungUp = """
        {"id":"d63f9a2c-aea5-4b77-875e-eee7ce1fd734","title":"Hung Up","artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}],"relations":[{"type":"edit","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"0f8f9997-2c5e-4180-a618-3013f9419157","title":"Hung Up","video":false,"artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}]}},
        {"type":"mashes up","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"20d73104-d32a-4d46-9876-f580d6dd7041","title":"Alejandro vs. Hung Up (Lady Gaga vs. Madonna)","video":false,"artist-credit":[{"name":"DJs From Mars","joinphrase":"","artist":{"id":"1d67b0cc-0a8b-44d3-8105-a8853af03f76","name":"DJs From Mars"}}]}},
        {"type":"remix","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"746b556a-b951-4ae6-95ee-685380f93108","title":"Hung Up (Chus & Ceballos remix)","video":false,"artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}]}},
        {"type":"remix","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"01159eb5-7ded-46a3-96d1-49be1562763f","title":"Hung Up (SDP extended vocal)","video":false,"artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}]}},
        {"type":"remix","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"6a19425a-6e7e-4133-a744-cab2a144a287","title":"Hung Up (Tracy Young’s Get Up and Dance groove edit)","video":false,"artist-credit":[{"name":"Madonna","joinphrase":"","artist":{"id":"79239441-bfd5-4981-a70c-55c3f15c1287","name":"Madonna"}}]}},
        {"type":"samples material","direction":"forward","target-type":"recording","attributes":[],"recording":{"id":"e1b7b099-abe6-4b7f-b06e-2c7cba7ffe7f","title":"Gimme! Gimme! Gimme! (A Man After Midnight)","video":false,"artist-credit":[{"name":"ABBA","joinphrase":"","artist":{"id":"d87e52c5-bb8d-4da8-b941-9f4928627dc8","name":"ABBA"}}]}},
        {"type":"performance","direction":"forward","target-type":"work","attributes":[],"work":{"id":"6fcc319a-4618-3c8f-88f6-d6e6bb32d5ba","title":"Hung Up","type":"Song"}}]}
    """

    // /ws/2/recording/25d8de5e-...?inc=work-rels+recording-rels+artist-credits
    private val hurt = """
        {"id":"25d8de5e-3662-4ffd-8dea-511a696ac3e7","title":"Hurt","artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}],"relations":[{"type":"mashes up","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"d6efa2cf-1c67-4c6a-b419-b6f429bb3b2b","title":"Hurt Like Poo (Johnny Cash vs. OutKast)","video":false,"artist-credit":[{"name":"Filip Čihák","joinphrase":"","artist":{"id":"f239aab1-2592-432a-93f6-3d10d17c69c6","name":"Filip Čihák"}}]}},
        {"type":"mashes up","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"c12ddfe3-c5fd-421b-8c3c-d16d5d5da58a","title":"Johnny","video":false,"artist-credit":[{"name":"Neil Cicierega","joinphrase":"","artist":{"id":"68f8885e-0752-480f-bf84-6d2fc67cff80","name":"Neil Cicierega"}}]}},
        {"type":"music video","direction":"forward","target-type":"recording","attributes":[],"recording":{"id":"51df1415-587a-4417-8321-df0a61fad6e4","title":"Hurt","video":true,"artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}]}},
        {"type":"performance","direction":"forward","target-type":"work","attributes":["cover"],"work":{"id":"ca56bd56-0e88-35f3-aec2-12eb8e9c739e","title":"Hurt","type":"Song"}}]}
    """

    // /ws/2/work/ca56bd56-...?inc=recording-rels+artist-rels (the performances of the recordings below, and its writers)
    private val hurtWork = """
        {"id":"ca56bd56-0e88-35f3-aec2-12eb8e9c739e","title":"Hurt","type":"Song","relations":[{"type":"composer","direction":"backward","target-type":"artist","attributes":[],"artist":{"id":"bc880664-6751-4998-984d-ebdbff15d229","name":"Trent Reznor"}},
        {"type":"lyricist","direction":"backward","target-type":"artist","attributes":[],"artist":{"id":"bc880664-6751-4998-984d-ebdbff15d229","name":"Trent Reznor"}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"144ac763-fefc-40f6-a5bd-63dad154d4a5","title":"Eraser / Hurt / The Downward Spiral","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"5c525e20-6654-4c35-98c7-1c91c1dd2972","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"071593f3-0cd3-4653-8805-2e457a6a21bf","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover","live"],"recording":{"id":"046f4200-1861-40a9-8bbc-f01ccca84f09","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover","live"],"recording":{"id":"faea1de3-8641-492a-bd38-52c86e47b7ee","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"10e5749e-e62f-42d0-a358-6c7584f13f64","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"ec9986f0-48d1-4772-b034-8d05336b5ee7","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"01039b22-b21d-4e65-820b-f7f5c8f31f92","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover","live"],"recording":{"id":"3d6175e0-0fca-42ce-9013-9881ab3ff09b","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"0301bcb7-938e-455c-b823-0e4c2b1a68b7","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover","live"],"recording":{"id":"f4dba0f2-3a9a-43b3-8a48-036f3fbb4938","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"f1710ce2-c5ff-4547-91fd-1450979619ed","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"1af163b8-5f6a-44db-99d6-7cd3da71ed42","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"58e5c882-f8e9-4724-a4c8-0347c2d75bfb","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"cf94f0eb-11ff-44c2-ab74-39e2c0b37b4b","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover","live"],"recording":{"id":"64b64c44-58af-456e-8449-8f3f8f5aca24","title":"Hurt","video":true}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"be00e394-76bb-40b8-9b8f-980d30eb2428","title":"Hurt","video":true}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"32c9709f-d35a-4d75-85d3-f53b3ed9d2cb","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"f62a520d-3ea3-4876-aa01-3ec6efe76c25","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["cover"],"recording":{"id":"5f4cc207-4b60-4767-854a-e8cf0fabb868","title":"Hurt","video":false}},
        {"type":"performance","direction":"backward","target-type":"recording","attributes":["live"],"recording":{"id":"0887030e-40e2-4adc-b20e-1b85e6e228a2","title":"Hurt","video":true}}]}
    """

    // /ws/2/recording?work=ca56bd56-...&inc=artist-credits&limit=100 (some of its first hundred)
    private val hurtRecordings = """
        {"recording-count":301,"recording-offset":0,"recordings":[{"id":"01039b22-b21d-4e65-820b-f7f5c8f31f92","title":"Hurt","video":false,"first-release-date":"2014","artist-credit":[{"name":"Nine Inch Nails","joinphrase":"","artist":{"id":"b7ffd2af-418f-4be2-bdd1-22f8b48613da","name":"Nine Inch Nails"}}]},
        {"id":"0301bcb7-938e-455c-b823-0e4c2b1a68b7","title":"Hurt","video":false,"first-release-date":"","artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}]},
        {"id":"046f4200-1861-40a9-8bbc-f01ccca84f09","title":"Hurt","video":false,"first-release-date":"2003","artist-credit":[{"name":"Sheryl Crow","joinphrase":"","artist":{"id":"80ccfede-c258-4575-a7ad-c982e9932e0f","name":"Sheryl Crow"}}]},
        {"id":"071593f3-0cd3-4653-8805-2e457a6a21bf","title":"Hurt","video":false,"first-release-date":"2018-10-12","artist-credit":[{"name":"Nine Inch Nails","joinphrase":" with ","artist":{"id":"b7ffd2af-418f-4be2-bdd1-22f8b48613da","name":"Nine Inch Nails"}},{"name":"David Bowie","joinphrase":"","artist":{"id":"5441c29d-3602-4898-b1a1-b77fa23b8e50","name":"David Bowie"}}]},
        {"id":"0887030e-40e2-4adc-b20e-1b85e6e228a2","title":"Hurt","video":true,"first-release-date":"2006-12","artist-credit":[{"name":"Nine Inch Nails","joinphrase":"","artist":{"id":"b7ffd2af-418f-4be2-bdd1-22f8b48613da","name":"Nine Inch Nails"}}]},
        {"id":"10e5749e-e62f-42d0-a358-6c7584f13f64","title":"Hurt","video":false,"first-release-date":"2006","artist-credit":[{"name":"Trent Reznor","joinphrase":" & ","artist":{"id":"bc880664-6751-4998-984d-ebdbff15d229","name":"Trent Reznor"}},{"name":"Peter Murphy","joinphrase":"","artist":{"id":"b78346af-e3ce-4b36-a0d5-032414de8a27","name":"Peter Murphy"}}]},
        {"id":"144ac763-fefc-40f6-a5bd-63dad154d4a5","title":"Eraser / Hurt / The Downward Spiral","video":false,"first-release-date":"","artist-credit":[{"name":"Nine Inch Nails","joinphrase":"","artist":{"id":"b7ffd2af-418f-4be2-bdd1-22f8b48613da","name":"Nine Inch Nails"}}]},
        {"id":"1af163b8-5f6a-44db-99d6-7cd3da71ed42","title":"Hurt","video":false,"first-release-date":"","artist-credit":[{"name":"Trent Reznor","joinphrase":" and ","artist":{"id":"bc880664-6751-4998-984d-ebdbff15d229","name":"Trent Reznor"}},{"name":"Peter Murphy","joinphrase":"","artist":{"id":"b78346af-e3ce-4b36-a0d5-032414de8a27","name":"Peter Murphy"}}]},
        {"id":"32c9709f-d35a-4d75-85d3-f53b3ed9d2cb","title":"Hurt","video":false,"first-release-date":"","artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}]},
        {"id":"3d6175e0-0fca-42ce-9013-9881ab3ff09b","title":"Hurt","video":false,"first-release-date":"2024-06-18","artist-credit":[{"name":"Pearl Jam","joinphrase":"","artist":{"id":"83b9cbe7-9857-49e2-ab8e-b57b01038103","name":"Pearl Jam"}}]},
        {"id":"58e5c882-f8e9-4724-a4c8-0347c2d75bfb","title":"Hurt","video":false,"first-release-date":"2011-12-09","artist-credit":[{"name":"Leona Lewis","joinphrase":"","artist":{"id":"8d552dfc-648f-401f-90de-e925013ca537","name":"Leona Lewis"}}]},
        {"id":"5c525e20-6654-4c35-98c7-1c91c1dd2972","title":"Hurt","video":false,"first-release-date":"1995","artist-credit":[{"name":"David Bowie","joinphrase":" with ","artist":{"id":"5441c29d-3602-4898-b1a1-b77fa23b8e50","name":"David Bowie"}},{"name":"Nine Inch Nails","joinphrase":"","artist":{"id":"b7ffd2af-418f-4be2-bdd1-22f8b48613da","name":"Nine Inch Nails"}}]},
        {"id":"5f4cc207-4b60-4767-854a-e8cf0fabb868","title":"Hurt","video":false,"first-release-date":"2003","artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}]},
        {"id":"64b64c44-58af-456e-8449-8f3f8f5aca24","title":"Hurt","video":true,"first-release-date":"2004-05-04","artist-credit":[{"name":"Sevendust","joinphrase":"","artist":{"id":"52b9e109-44a0-45eb-a197-226c4abab232","name":"Sevendust"}}]},
        {"id":"be00e394-76bb-40b8-9b8f-980d30eb2428","title":"Hurt","video":true,"first-release-date":"2006-01-27","artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}]},
        {"id":"cf94f0eb-11ff-44c2-ab74-39e2c0b37b4b","title":"Hurt","video":false,"first-release-date":"2010","artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}]},
        {"id":"ec9986f0-48d1-4772-b034-8d05336b5ee7","title":"Hurt","video":false,"first-release-date":"2009-11-24","artist-credit":[{"name":"Trent Reznor","joinphrase":"","artist":{"id":"bc880664-6751-4998-984d-ebdbff15d229","name":"Trent Reznor"}}]},
        {"id":"f1710ce2-c5ff-4547-91fd-1450979619ed","title":"Hurt","video":false,"first-release-date":"2001-02-06","artist-credit":[{"name":"Necromantik Sunshine & Oko Jumu","joinphrase":"","artist":{"id":"638b73f4-a96d-4af7-8d01-43e4438ae799","name":"Necromantik Sunshine & Oko Jumu"}}]},
        {"id":"f4dba0f2-3a9a-43b3-8a48-036f3fbb4938","title":"Hurt","video":false,"first-release-date":"2004-05-04","artist-credit":[{"name":"Sevendust","joinphrase":"","artist":{"id":"52b9e109-44a0-45eb-a197-226c4abab232","name":"Sevendust"}}]},
        {"id":"f62a520d-3ea3-4876-aa01-3ec6efe76c25","title":"Hurt","video":false,"first-release-date":"2004","artist-credit":[{"name":"Johnny Cash","joinphrase":"","artist":{"id":"d43d12a1-2dc9-4257-a2fd-0a3bb1081b86","name":"Johnny Cash"}}]},
        {"id":"faea1de3-8641-492a-bd38-52c86e47b7ee","title":"Hurt","video":false,"first-release-date":"","artist-credit":[{"name":"Sheryl Crow","joinphrase":"","artist":{"id":"80ccfede-c258-4575-a7ad-c982e9932e0f","name":"Sheryl Crow"}}]}]}
    """

    @Test fun picksTheAlbumsOwnTake() {
        // the one on 39 releases, not a DJ-mix's part or a live one
        assertEquals("d63f9a2c-aea5-4b77-875e-eee7ce1fd734", Lineage.pick(search, "Hung Up", "Madonna"))
        assertEquals(Lineage.pick(search, "Hung Up", "Madonna"), Lineage.pick(search, "Hung Up (Remastered)", "Madonna"))
        assertNull(Lineage.pick(search, "Hung Up", "ABBA"))
        assertNull(Lineage.pick(search, "Sorry", "Madonna"))
    }

    @Test fun samplesAndRemixes() {
        val r = Lineage.recording(hungUp)
        assertEquals(listOf(Lineage.Song("Gimme! Gimme! Gimme! (A Man After Midnight)", "ABBA")), r.samples)
        assertTrue(r.sampledIn.isEmpty())
        assertTrue(r.remixOf.isEmpty())
        assertEquals(3, r.remixes.size)
        assertEquals(Lineage.Song("Hung Up (Chus & Ceballos remix)", "Madonna"), r.remixes.first())
        assertEquals("6fcc319a-4618-3c8f-88f6-d6e6bb32d5ba", r.work)
        assertFalse(r.cover)
    }

    @Test fun aCoversOriginalAndOtherCovers() {
        val r = Lineage.recording(hurt)
        assertTrue(r.cover)
        assertEquals("ca56bd56-0e88-35f3-aec2-12eb8e9c739e", r.work)
        // its mash-ups and music video aren't family
        assertTrue(r.samples.isEmpty() && r.sampledIn.isEmpty() && r.remixes.isEmpty() && r.remixOf.isEmpty())
        val (original, covers) = Lineage.work(hurtWork, hurtRecordings, "Johnny Cash", r.cover)
        assertEquals(Lineage.Song("Hurt", "Nine Inch Nails"), original)
        // who covered it most first (videos aside: one of Sevendust's two), then the earliest; never Cash himself
        assertEquals(listOf("Sheryl Crow", "Necromantik Sunshine & Oko Jumu", "Sevendust", "Leona Lewis", "Pearl Jam"), covers.map { it.artist })
    }

    @Test fun theOriginalsOwnHasNoOriginal() {
        val (original, covers) = Lineage.work(hurtWork, hurtRecordings, "Nine Inch Nails", false)
        assertNull(original)
        assertEquals("Johnny Cash", covers.first().artist)
    }

    @Test fun bareTitles() {
        assertEquals("Hurt", Lineage.bare("Hurt (Remastered 2011)"))
        assertEquals("Hurt", Lineage.bare("Hurt - 2011 Remaster"))
        assertEquals("Hung Up", Lineage.bare("Hung Up [Radio Version]"))
    }
}
