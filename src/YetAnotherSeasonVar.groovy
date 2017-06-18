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
        return url ==~ WEB_RESOURCE_URL_PATTERN
    }

    @Override
    int getVersion() {
        return 2
    }

    @Override
    String getExtractorName() {
        return this.getClass().getName()
    }

    @Override
    protected WebResourceContainer extractItems(URL url, int i) {
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
