package zzz.service.forum.utils;

/**
 * @Description 打印彩色字体
 * @author nanno
 * @param[1] null
 * @time 2023/6/9 17:22
 */
public class PrintColor {
    public static void color(String color, String str) {
        switch (color) {
            case "red":
            case "hong":
                System.out.println("\033[31;4m" + str + "\033[0m");
                break;
            // 如果为green或者lv，就打印绿色字体
            case "green":
            case "lv":
                System.out.println("\033[32;4m" + str + "\033[0m");
                break;
            case "yellow":
            case "huang":
            case "gold":
            case "jin":
                System.out.println("\033[33;4m" + str + "\033[0m");
                break;
            case "blue":
            case "lan":
                System.out.println("\033[34;4m" + str + "\033[0m");
                break;
            case "purple":
            case "zi":
                System.out.println("\033[35;4m" + str + "\033[0m");
                break;
            case "cyan":
            case "qing":
                System.out.println("\033[36;4m" + str + "\033[0m");
                break;
            case "white":
            case "bai":
                System.out.println("\033[37;4m" + str + "\033[0m");
                break;
            case "orange":
            case "cheng":
                System.out.println("\033[38;4m" + str + "\033[0m");
                break;
            case "pink":
            case "fen":
                System.out.println("\033[95;4m" + str + "\033[0m");
                break;
            case "silver":
            case "yin":
                System.out.println("\033[37;1m" + str + "\033[0m");
                break;
            case "gray":
            case "hui":
                System.out.println("\033[90;1m" + str + "\033[0m");
                break;
            default:
                System.out.println("\033[30;4m" + str + "\033[0m");
                break;
        }
    }

    //不传颜色默认红色
    public static void color(String str) {
        System.out.println("\033[31;4m" + str + "\033[0m");
    }
    public static void zi(String str) {
        color("zi", str);
    }
    public static void hong(String str) {
        color("hong", str);
    }
    public static void lv(String str) {
        color("lv", str);
    }
    public static void huang(String str) {
        color("huang", str);
    }
    public static void lan(String str) {
        color("lan", str);
    }
    public static void qing(String str) {
        color("qing", str);
    }
    public static void bai(String str) {
        color("bai", str);
    }
    public static void red(String str) {
        color("red", str);
    }
    public static void green(String str) {
        color("green", str);
    }
    public static void yellow(String str) {
        color("yellow", str);
    }
    public static void blue(String str) {
        color("blue", str);
    }
    public static void purple(String str) {
        color("purple", str);
    }
    public static void cyan(String str) {
        color("cyan", str);
    }
    public static void white(String str) {
        color("white", str);
    }
    public static void lanliang(String str) {
        color("lanliang", str);
    }
    public static void cheng(String str) {
        color("cheng", str);
    }
    public static void orange(String str) {
        color("orange", str);
    }
    public static void pink(String str) {
        color("pink", str);
    }
    public static void gold(String str) {
        color("gold", str);
    }
    public static void jin(String str) {
        color("jin", str);
    }
    public static void silver(String str) {
        color("silver", str);
    }
    public static void yin(String str) {
        color("yin", str);
    }
    public static void gray(String str) {
        color("gray", str);
    }
    public static void hui(String str) {
        color("hui", str);
    }




}
