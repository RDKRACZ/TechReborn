package techreborn.blocks;

import reborncore.common.registration.RebornRegistry;
import reborncore.common.registration.impl.ConfigRegistry;
import techreborn.lib.ModInfo;


//This is in its own class as not to load the block class before
@RebornRegistry(modID = ModInfo.MOD_ID, priority = 1, earlyReg = true)
public class OreBlockStateManager {
	//This is a one off config, dont worry about it
	@ConfigRegistry(config = "misc", category = "misc", key = "endOreStone", comment = "Set to true to render the end ores with a stone background")
	public static boolean endOreStone = false;

	public static String convert(String name){
		if (OreBlockStateManager.endOreStone && name.equals("tungsten")) {
			name = "tungsten_stone";
		}
		return name;
	}

	public static String invert(String name){
		if (name.equals("tungsten_stone")) {
			name = "tungsten";
		}
		return name;
	}
}
