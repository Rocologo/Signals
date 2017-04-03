package com.minemaarten.signals.client;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.minemaarten.signals.init.ModBlocks;

public class CreativeTabSignals extends CreativeTabs{
    private static final CreativeTabSignals INSTANCE = new CreativeTabSignals("signals");

    public static CreativeTabSignals getInstance(){
        return INSTANCE;
    }

    public CreativeTabSignals(String name){
        super(name);
    }

    @Override
    public ItemStack getTabIconItem(){
        return new ItemStack(Item.getItemFromBlock(ModBlocks.blockSignal));
    }

}
