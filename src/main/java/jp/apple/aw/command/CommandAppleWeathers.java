package jp.apple.aw.command;

import jp.apple.aw.weather.WeatherManager;
import jp.apple.aw.weather.WeatherType;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

public class CommandAppleWeathers extends CommandBase {
    @Override
    public String getName() {
        return "aw";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "./aw weather <weather:sunny|cloudy|rainy|snowy|stormy> <meta:0|1|2>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString("§c引数が足りません。使い方: " + getUsage(sender)));
            return;
        }
        if (args[0].equalsIgnoreCase("weather")) {
            String weatherInput = args[1].toUpperCase();
            int meta = 0;

            boolean needsMeta = weatherInput.equals("RAINY") || weatherInput.equals("SNOWY");
            if (needsMeta) {
                if (args.length < 3) {
                    sender.sendMessage(new TextComponentString("§cこの天候はmeta値が必要です。例: ./aw weather rainy 1"));
                    return;
                }
                meta = Integer.parseInt(args[2]);
                if (meta < 0 || meta > 2) {
                    sender.sendMessage(new TextComponentString("§cmeta値は 0〜2 の範囲で指定してください。"));
                    return;
                }
            }
            WeatherType targetWeather = null;
            switch (weatherInput) {
                case "RAINY":
                    switch (meta) {
                        case 0:
                            targetWeather = WeatherType.LIGHT_RAINY;
                            break;
                        case 1:
                            targetWeather = WeatherType.RAINY;
                            break;
                        case 2:
                            targetWeather = WeatherType.HEAVY_RAINY;
                            break;
                        default:
                            throw new CommandException("§c不正なmeta値です。");
                    }
                    break;
                case "SNOWY":
                    switch (meta) {
                        case 0:
                            targetWeather = WeatherType.LIGHT_SNOWY;
                            break;
                        case 1:
                            targetWeather = WeatherType.SNOWY;
                            break;
                        case 2:
                            targetWeather = WeatherType.HEAVY_SNOWY;
                            break;
                        default:
                            throw new CommandException("§c不正なmeta値です。");
                    }
                    break;
                default:
                    try {
                        targetWeather = WeatherType.valueOf(weatherInput);
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(new TextComponentString("§c無効な天候です。"));
                        return;
                    }
            }
            WeatherManager.currentWeather = targetWeather;
            sender.sendMessage(new TextComponentString("§a[AppleWeathers] 天候を[" + targetWeather.getLabel() + "]に変更しました。"));
        }
    }
}
