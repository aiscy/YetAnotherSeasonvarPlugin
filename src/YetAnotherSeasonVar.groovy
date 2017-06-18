import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.zip.GZIPInputStream

import org.serviio.library.metadata.MediaFileType
import org.serviio.library.online.ContentURLContainer
import org.serviio.library.online.PreferredQuality
import org.serviio.library.online.WebResourceContainer
import org.serviio.library.online.WebResourceItem
import org.serviio.library.online.WebResourceUrlExtractor


class YetAnotherSeasonVar extends WebResourceUrlExtractor {
    private static final WEB_RESOURCE_URL_PATTERN = /^(?:http?:\\/\\/)?(?:www\.)?seasonvar\.ru\\/.*/
    private Connector connection = new Connector()

    @Override
    boolean extractorMatches(URL url) {
        /**
         * Called once for the whole feed.
         * For each feed which needs a plugin Serviio tries to match all available plugins to the feed's URL by
         * calling this method and uses the first plugin that returns true. Use of regular expression is
         * recommended.
         *
         * @param url URL of the whole feed, as entered by the user
         * @return returns true if the feed's items can be processed by this plugin
         */
        return url ==~ WEB_RESOURCE_URL_PATTERN
    }

    @Override
    int getVersion() {
        /**
         * @return returns the version of this plugin. Defaults to “1” if the method is not implemented.
         */
        return 2
    }

    @Override
    String getExtractorName() {
        /**
         * @return returns the name of this plugin. Is mostly used for logging and reporting purposes.
         */
        return this.getClass().getName()
    }

    @Override
    protected WebResourceContainer extractItems(URL url, int i) {
        /**
         * Performs the extraction of basic information about the resource.
         * If the object cannot be constructed the method should return null or throw an exception.
         *
         * @param url URL of the resource to be extracted. The plugin will have to get the contents on the URL itself.
         * @param i Max. number of items the user prefers to get back or -1 for unlimited. It is
         * up to the plugin designer to decide how to limit the results (if at all).
         * @return returns an instance of org.serviio.library.online.WebResourceContainer.
         * These are the properties of the class:
         * • String title – title of the web resource; optional
         * • String thumbnailUrl – URL of the resource's thumbnail; optional
         * • List<org.serviio.library.online.WebResourceItem> items – list of extracted content items
         * WebResourceItem represents basic information about an item and has these properties:
         * • String title – title of the content item; mandatory
         * • Date releaseDate – release date of the content; optional
         * • Map<String,String> additionalInfo – a map of key – value pairs that can include
         * information needed to retrieve content URL of the item (see extractUrl() method)
         * • String cacheKey – if present, the URL extracted on Serviio startup will be cached, and
         * reused rather than re-extracted on subsequent feed refreshes, unless the item has explicitly
         * expired due to the related ContentURLContainer's expiresOn value or the feed is forcibly
         * refreshed by the user.
         */
        String userPreferredTranslation
        String urlQuery = url.getQuery()
        if (urlQuery) {
            String translation = URLDecoder.decode(urlQuery, "UTF-8").split("&").find { it.matches(/(?i)translation=.+/) }
            if (translation != null) {
                userPreferredTranslation = translation.split("=")[1].toLowerCase()
            }
        }
        String indexHTML = this.connection.GET(url)
        def params = ["type": "html5"] << Regex.secureAndTime(indexHTML) << Regex.idAndSerial(indexHTML) as HashMap<String, String>
        String playerHTML = this.connection.POST(new URL("http://seasonvar.ru/player.php"), url, params)
        HashMap<String, String> translationVariants = Regex.translations(playerHTML)
        String title = Regex.title(indexHTML).replace("онлайн", "")
        String playlist
        if (userPreferredTranslation != null && translationVariants.containsKey(userPreferredTranslation)) {
            playlist = translationVariants.get(userPreferredTranslation)
            title = "$title $userPreferredTranslation"
        } else {
            playlist = Regex.playlist(playerHTML)
        }
        String jsonText = this.connection.GET(new URL("http://${Connector.baseURL}${playlist}"))
        def json = new JsonSlurper().parseText(jsonText).playlist

        return new WebResourceContainer(
                title: title,
                thumbnailUrl: "http://cdn.seasonvar.ru/oblojka/large/${params.id}.jpg",
                items: json.collect { item ->
                    new WebResourceItem(
                            title: item.comment.replace("<br>", " ") as String,
                            additionalInfo: [
                                    'link': item.file as String])
                }
        )
    }

    @Override
    protected ContentURLContainer extractUrl(WebResourceItem webResourceItem, PreferredQuality preferredQuality) {
        /**
         * This method is called once for each item included in the created WebResourceContainer.
         * Performs the actual extraction of content information using the provided information.
         * If the object cannot be constructed the method should return null or throw an exception.
         *
         * @param webResourceItem an instance of org.serviio.library.online.WebResourceItem, as created in
         * extractItems() method.
         * @param preferredQuality includes value (HIGH, MEDUIM, LOW) of enumeration
         * org.serviio.library.online.PreferredQuality. It should be taken into consideration if the
         * online service offers multiple quality-based renditions of the content.
         * @return returns an
         * instance of org.serviio.library.online.ContentURLContainer. These are the properties of the class:
         * • String contentUrl – URL of the feed item's content; mandatory
         * • String thumbnailUrl – URL of the feed item's thumbnail; optional
         * • org.serviio.library.metadata.MediaFileType fileType – file type of the feed item; default is VIDEO
         * • Date expiresOn – a date the feed item expires on. It can mean the item itself expires or the
         * item's contentUrl expires; the whole feed will be parsed again on the earliest expiry date of
         * all feed items; optional
         * • boolean expiresImmediately – if true Serviio will extract the URL again when the play
         * request is received to get URL that is valid for the whole playback duration. Note this is
         * related to the content URL only, the feed item itself should still be valid and available; optional
         * • String cacheKey – a unique identifier of the content (i.e. this item with this quality) used
         * as a key to technical metadata cache; required if either expiresOn and/or expiresImmediately is provided
         * • boolean live – identifies the content as a live stream; optional (default is false)
         * • String userAgent – specifies a particular User-Agent HPPT header to use when retrieving the content
         */
        return new ContentURLContainer(
                fileType: MediaFileType.VIDEO,
                thumbnailUrl: webResourceItem.additionalInfo.thumbnailUrl,
                contentUrl: webResourceItem.additionalInfo.link,
                userAgent: Connector.headers["User-Agent"])
    }
}

class Regex {
    static HashMap<String, String> secureAndTime(String html) {
        Matcher matcher = html =~ /(?s)var data4play = .*?'secureMark': '([a-f0-9]+)',.*?'time': ([0-9]+).*?/
        matcher.find() // TODO
        return ["secure": matcher.group(1), "time": matcher.group(2)]
    }

    static HashMap<String, String> idAndSerial(String html) {
        Matcher matcher = html =~ /data-id-season="(\d+)" data-id-serial="(\d+)"/
        matcher.find() // TODO
        return ["id": matcher.group(1), "serial": matcher.group(2)]
    }

    static ArrayList<String> seasons(String html) {
        Matcher matcher = html =~ /(?s)<h2>.*?href="(\/serial-\d+-[^-.]+(?:-\d+-(?:sezon|season))?\.html)".*?/
        return matcher.iterator().collect { matcher.group(1) }
    }

    static String playlist(String html) {
        Matcher matcher = html =~ /var pl = \{'0': "(.+)"};/
        matcher.find() // TODO
        return matcher.group(1)
    }

    static HashMap<String, String> translations(String html) {
        Matcher matcher = html =~ /(?s)<ul class="pgs-trans"(.*?)<\/ul>/
        if (matcher.find()) {
            Matcher m = matcher.group(0) =~ /(?s)<li data-click="translate[^>]*?>([^<]+)<\/li>[\s\n]*?<script>pl\[\d+] = "(.*?)";/
            HashMap<String, String> translationVariants = [:]
            m.iterator().each {
                [translationVariants[m.group(1).toLowerCase()] = m.group(2)]
            }
            return translationVariants
        } else {
            return [:]
        }
    }

    static String title(String html) {
        Matcher matcher = html =~ /(?s)<h1 class="pgs-sinfo-title" itemprop="name">(.*?)<\/h1>/
        if (matcher.find()) {
            return matcher.group(1)
        } else {
            return ""
        }

    }
}

class Connector {
    static final String baseURL = "seasonvar.ru"
    static final def headers = [
            "User-Agent"     : "Mozilla/5.0 (iPad; CPU OS 9_1 like Mac OS X) \
AppleWebKit/601.1.46 (KHTML, like Gecko) \
Version/9.0 Mobile/13B143 Safari/601.1",
            "Host"           : baseURL,
            "Accept-Language": "ru-RU",
            "Origin"         : "http://${baseURL}",
            "Accept"         : "*/*",
            "Accept-Encoding": "gzip, deflate"]
    static final def cookies = [
            "html5default"     : "1",
            "uppodhtml5_volume": "1",
            "IIIIIIIIIIIIIIIII": "WerTylv_tr",
            "sva"              : "lVe324PqsI24"]
    private Proxy proxy

    Connector(String hostname, int port) {
        this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hostname, port))
    }

    Connector() {
        this.proxy = Proxy.NO_PROXY
    }

    private HttpURLConnection makeConnection(URL url) {
        HttpURLConnection connection = url.openConnection(this.proxy) as HttpURLConnection
        headers.each { key, value ->
            connection.setRequestProperty(key, value)
        }
        String cookiesFlat = cookies.collect { key, value -> /$key=$value/ }.join("; ")
        connection.setRequestProperty("Cookie", cookiesFlat)
        return connection
    }

    private static String decodeText(HttpURLConnection connection) {
        if (connection.getContentEncoding() == "gzip") {
            return IOUtils.toString(new GZIPInputStream(connection.inputStream), StandardCharsets.UTF_8)
        } else {
            return IOUtils.toString(connection.inputStream, StandardCharsets.UTF_8)
        }
    }

    String GET(URL url) {
        HttpURLConnection connection = this.makeConnection(url)
        connection.setRequestMethod("GET")
        connection.connect()
        return decodeText(connection)
    }

    String POST(URL url, URL referer, HashMap<String, String> params) {
        HttpURLConnection connection = this.makeConnection(url)
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Referer", "${referer.getProtocol()}://${referer.getHost()}${referer.getPath()}")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        OutputStreamWriter writer = new OutputStreamWriter(connection.outputStream)
        String data = params.collect { key, value ->
            /${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}/
        }.join("&")
        writer.write(data)
        writer.flush()
        writer.close()
        connection.connect()
        return decodeText(connection)
    }
}

class Main {
    static void main(String[] args) {
        YetAnotherSeasonVar extractor = new YetAnotherSeasonVar()
//        URL testUrl = new URL("http://seasonvar.ru/serial-15485-Luchshe_zvonite_Solu-3-season.html?traNslation=")
        URL testUrl = new URL("http://seasonvar.ru/serial-529-Ideal-1-season.html?traNslation=%D0%9A%D1%83%D0%B1%D0%98%D0%BA%20%D0%B2%20%D0%9A%D1%83%D0%B1%D0%B5")
        println(extractor.getExtractorName())
        if (extractor.extractorMatches(testUrl)) {
            WebResourceContainer container = extractor.extractItems(testUrl, -1)
            def items = container.getItems()
            items.each {
                println(extractor.extractUrl(it, PreferredQuality.HIGH))
            }
        }

    }
}
