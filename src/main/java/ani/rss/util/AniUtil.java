package ani.rss.util;

import ani.rss.entity.Ani;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class AniUtil {

    public static final List<Ani> ANI_LIST = new CopyOnWriteArrayList<>();

    /**
     * 获取订阅配置文件
     *
     * @return
     */
    public static File getAniFile() {
        File configDir = ConfigUtil.getConfigDir();
        return new File(configDir + File.separator + "ani.json");
    }

    /**
     * 加载订阅
     */
    public static void load() {
        File configFile = getAniFile();

        if (!configFile.exists()) {
            FileUtil.writeUtf8String(GsonStatic.toJson(ANI_LIST), configFile);
        }
        String s = FileUtil.readUtf8String(configFile);
        List<Ani> anis = GsonStatic.fromJsonList(s, Ani.class);
        for (Ani ani : anis) {
            Ani newAni = Ani.bulidAni();
            BeanUtil.copyProperties(ani, newAni, CopyOptions
                    .create()
                    .setIgnoreNullValue(true));
            ANI_LIST.add(newAni);
        }
        log.debug("加载订阅 共{}项", ANI_LIST.size());


        // 处理旧数据
        for (Ani ani : ANI_LIST) {
            // 备用rss数据结构改变
            List<Ani.BackRss> backRssList = ani.getBackRssList();
            List<String> backRss = ani.getBackRss();
            if (backRssList.isEmpty() && !backRss.isEmpty()) {
                for (String rss : backRss) {
                    backRssList.add(
                            new Ani.BackRss()
                                    .setLabel("未知字幕组")
                                    .setUrl(rss)
                    );
                }
            }
            for (Ani.BackRss rss : backRssList) {
                Integer offset = rss.getOffset();
                offset = ObjectUtil.defaultIfNull(offset, ani.getOffset());
                rss.setOffset(offset);
            }
        }
    }

    /**
     * 将订阅配置保存到磁盘
     */
    public static synchronized void sync() {
        File configFile = getAniFile();
        log.debug("保存订阅 {}", configFile);
        try {
            String json = GsonStatic.toJson(ANI_LIST);
            File temp = new File(configFile + ".temp");
            FileUtil.del(temp);
            FileUtil.writeUtf8String(json, temp);
            FileUtil.rename(temp, configFile.getName(), true);
            log.debug("保存成功 {}", configFile);
        } catch (Exception e) {
            log.error("保存失败 {}", configFile);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 获取动漫信息
     *
     * @param url
     * @return
     */
    public static Ani getAni(String url, String type, String bgmUrl) {
        Config config = ConfigUtil.CONFIG;
        type = StrUtil.blankToDefault(type, "mikan");
        String subgroupId = MikanUtil.getSubgroupId(url);

        Ani ani = Ani.bulidAni();
        ani.setUrl(url.trim());

        if ("mikan".equals(type)) {
            try {
                MikanUtil.getMikanInfo(ani, subgroupId);
            } catch (Exception e) {
                throw new RuntimeException("获取失败");
            }
        } else {
            ani.setBgmUrl(bgmUrl)
                    .setSubgroup("未知字幕组");
        }

        BgmInfo bgmInfo = BgmUtil.getBgmInfo(ani, true);

        BgmUtil.toAni(bgmInfo, ani);

        String title;

        // 只下载最新集
        Boolean downloadNew = config.getDownloadNew();
        // 使用tmdb标题
        Boolean tmdb = config.getTmdb();
        // 默认启用全局排除
        Boolean enabledExclude = config.getEnabledExclude();
        // 默认导入全局排除
        Boolean importExclude = config.getImportExclude();
        // 全局排除
        List<String> exclude = config.getExclude();

        // 获取tmdb标题
        String themoviedbName = TmdbUtil.getName(ani);

        // 是否使用tmdb标题
        if (StrUtil.isNotBlank(themoviedbName) && tmdb) {
            title = themoviedbName;
        } else {
            title = BgmUtil.getName(bgmInfo, ani.getTmdb());
        }

        // 默认导入全局排除
        if (importExclude) {
            exclude = new ArrayList<>(exclude);
            exclude.addAll(ani.getExclude());
            exclude = exclude.stream().distinct().toList();
            ani.setExclude(exclude);
        }

        // 去除特殊符号
        title = RenameUtil.getName(title);

        ani
                // 再次保存标题
                .setTitle(title)
                // 只下载最新集
                .setDownloadNew(downloadNew)
                // 是否启用全局排除
                .setGlobalExclude(enabledExclude)
                // type mikan or other
                .setType(type)
                // tmdb标题
                .setThemoviedbName(themoviedbName);

        // 下载位置
        String downloadPath = FileUtil.getAbsolutePath(TorrentUtil.getDownloadPath(ani).get(0));
        ani.setDownloadPath(downloadPath);

        log.debug("获取到动漫信息 {}", JSONUtil.formatJsonStr(GsonStatic.toJson(ani)));
        if (ani.getOva()) {
            return ani;
        }

        // 自动推断剧集偏移
        if (config.getOffset()) {
            String s = HttpReq.get(url, true)
                    .timeout(config.getRssTimeout() * 1000)
                    .thenFunction(res -> {
                        Assert.isTrue(res.isOk(), "status: {}", res.getStatus());
                        return res.body();
                    });
            List<Item> items = ItemsUtil.getItems(ani, s, new Item());
            if (items.isEmpty()) {
                return ani;
            }
            Double offset = -(items.stream()
                    .map(Item::getEpisode)
                    .min(Comparator.comparingDouble(i -> i))
                    .get() - 1);
            log.debug("自动获取到剧集偏移为 {}", offset);
            ani.setOffset(offset.intValue());
        }
        return ani;
    }


    public static String saveJpg(String coverUrl) {
        return saveJpg(coverUrl, false);
    }

    /**
     * 保存图片
     *
     * @param coverUrl
     * @param isOverride 是否覆盖
     * @return
     */
    public static String saveJpg(String coverUrl, Boolean isOverride) {
        File configDir = ConfigUtil.getConfigDir();
        FileUtil.mkdir(configDir + "/files/");

        // 默认空图片
        String cover = "cover.png";
        if (!FileUtil.exist(configDir + "/files/" + cover)) {
            byte[] bytes = ResourceUtil.readBytes("image/cover.png");
            FileUtil.writeBytes(bytes, configDir + "/files/" + cover);
        }
        if (StrUtil.isBlank(coverUrl)) {
            return cover;
        }
        String filename = Md5Util.digestHex(coverUrl);
        filename = filename.charAt(0) + "/" + filename + "." + FileUtil.extName(URLUtil.getPath(coverUrl));
        FileUtil.mkdir(configDir + "/files/" + filename.charAt(0));
        File file = new File(configDir + "/files/" + filename);
        if (file.exists() && !isOverride) {
            return filename;
        }
        FileUtil.del(file);
        try {
            HttpReq.get(coverUrl, true)
                    .then(res -> FileUtil.writeFromStream(res.bodyStream(), file));
            return filename;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return cover;
        }
    }

    /**
     * 校验参数
     *
     * @param ani
     */
    public static void verify(Ani ani) {
        String url = ani.getUrl();
        List<String> exclude = ani.getExclude();
        Integer season = ani.getSeason();
        Integer offset = ani.getOffset();
        String title = ani.getTitle();
        Assert.notBlank(url, "RSS URL 不能为空");
        if (Objects.isNull(exclude)) {
            ani.setExclude(new ArrayList<>());
        }
        Assert.notNull(season, "季不能为空");
        Assert.notBlank(title, "标题不能为空");
        Assert.notNull(offset, "集数偏移不能为空");
    }


    /**
     * 获取蜜柑的bangumiId
     *
     * @param ani
     * @return
     */
    public static String getBangumiId(Ani ani) {
        String url = ani.getUrl();
        if (StrUtil.isBlank(url)) {
            return "";
        }
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);
        return decodeParamMap.get("bangumiId");
    }

}
