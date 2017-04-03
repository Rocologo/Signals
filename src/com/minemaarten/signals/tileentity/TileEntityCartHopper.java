package com.minemaarten.signals.tileentity;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.wrapper.InvWrapper;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.minemaarten.signals.api.ICartHopperBehaviour;
import com.minemaarten.signals.capabilities.CapabilityMinecartDestination;
import com.minemaarten.signals.network.GuiSynced;
import com.minemaarten.signals.rail.RailCacheManager;
import com.minemaarten.signals.rail.RailManager;
import com.minemaarten.signals.tileentity.carthopperbehaviour.CartHopperBehaviourItems;

public class TileEntityCartHopper extends TileEntityBase implements ITickable, IGUIButtonSensitive{
    public enum HopperMode{
        CART_FULL, CART_EMPTY, NO_ACTIVITY, NEVER
    }

    @GuiSynced
    private HopperMode hopperMode = HopperMode.CART_FULL;
    @GuiSynced
    private boolean interactEngine; //When true, the Cart Engine capability will be filled/emptied instead.
    private EnumFacing pushDir = EnumFacing.NORTH; //The direction the cart is pushed in when activated.
    private EntityMinecart managingCart;
    private UUID managingCartId;
    private boolean pushedLastTick;

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag){
        tag.setByte("hopperMode", (byte)hopperMode.ordinal());
        tag.setBoolean("interactEngine", interactEngine);
        tag.setByte("pushDir", (byte)pushDir.ordinal());
        tag.setBoolean("pushedLastTick", pushedLastTick);
        return super.writeToNBT(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag){
        super.readFromNBT(tag);
        hopperMode = HopperMode.values()[tag.getByte("hopperMode")];
        interactEngine = tag.getBoolean("interactEngine");
        pushDir = EnumFacing.values()[tag.getByte("pushDir")];
        pushedLastTick = tag.getBoolean("pushedLastTick");
    }

    @Override
    public void update(){
        if(!getWorld().isRemote) {
            if(managingCartId != null) {
                List<EntityMinecart> carts = getWorld().getEntities(EntityMinecart.class, new Predicate<EntityMinecart>(){
                    @Override
                    public boolean apply(EntityMinecart input){
                        return input.getPersistentID().equals(managingCartId);
                    }
                });
                managingCart = carts.isEmpty() ? null : carts.get(0);
                managingCartId = null;
            }

            boolean shouldPush = tryTransfer(RailCacheManager.getInstance(getWorld()).getRail(getWorld(), getPos().up()) != null);
            if(shouldPush && !pushedLastTick) pushCart();
            boolean notifyNeighbors = shouldPush != pushedLastTick;
            pushedLastTick = shouldPush;
            if(notifyNeighbors) {
                getWorld().notifyNeighborsOfStateChange(getPos(), getBlockType(), true);
            }
        }
    }

    public boolean emitsRedstone(){
        return pushedLastTick;
    }

    public HopperMode getHopperMode(){
        return hopperMode;
    }

    public boolean interactsWithEngine(){
        return interactEngine;
    }

    private void pushCart(){
        managingCart.motionX += pushDir.getFrontOffsetX() * 0.1;
        managingCart.motionZ += pushDir.getFrontOffsetZ() * 0.1;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean tryTransfer(boolean extract){
        updateManagingCart(new AxisAlignedBB(extract ? getPos().up() : getPos().down()));
        if(managingCart != null) {
            boolean active = false, empty = false, full = false;
            List<Pair<TileEntity, EnumFacing>> filters = Lists.newArrayList();
            for(EnumFacing dir : EnumFacing.HORIZONTALS) {
                TileEntity filter = getWorld().getTileEntity(getPos().offset(dir));
                if(filter != null) filters.add(new ImmutablePair<TileEntity, EnumFacing>(filter, dir));
            }

            for(ICartHopperBehaviour hopperBehaviour : RailManager.getInstance().getHopperBehaviours()) {
                Capability cap = hopperBehaviour.getCapability();
                if(interactEngine && hopperBehaviour instanceof CartHopperBehaviourItems || managingCart.hasCapability(cap, null)) {
                    Object cart = null;
                    if(interactEngine && hopperBehaviour instanceof CartHopperBehaviourItems) {
                        if(managingCart.hasCapability(CapabilityMinecartDestination.INSTANCE, null)) {
                            CapabilityMinecartDestination destCap = managingCart.getCapability(CapabilityMinecartDestination.INSTANCE, null);
                            if(destCap.isMotorized()) {
                                cart = new InvWrapper(destCap.getFuelInv());
                            }
                        }
                        if(cart == null) continue;
                    } else {
                        cart = managingCart.getCapability(cap, null);
                    }
                    Object te = getCapabilityAt(cap, extract ? EnumFacing.DOWN : EnumFacing.UP);
                    if(te != null && hopperBehaviour.tryTransfer(extract ? cart : te, extract ? te : cart, filters)) active = true;
                    if(hopperMode == HopperMode.CART_EMPTY && hopperBehaviour.isCartEmpty(cart, filters)) empty = true;
                    if(hopperMode == HopperMode.CART_FULL && hopperBehaviour.isCartFull(cart)) full = true;
                }
            }
            return hopperMode == HopperMode.NO_ACTIVITY ? !active : empty || full;
        }
        return false;
    }

    private void updateManagingCart(AxisAlignedBB aabb){
        if(managingCart != null) {
            if(managingCart.isDead || !managingCart.getEntityBoundingBox().intersectsWith(aabb)) {
                managingCart = null;
            }
        }
        if(managingCart == null) {
            List<EntityMinecart> carts = getWorld().getEntitiesWithinAABB(EntityMinecart.class, aabb);
            if(!carts.isEmpty()) {
                managingCart = carts.get(0);
                pushDir = managingCart.getAdjustedHorizontalFacing();
            }
        }
    }

    private <T> T getCapabilityAt(Capability<T> cap, EnumFacing dir){
        BlockPos pos = getPos().offset(dir);
        TileEntity te = getWorld().getTileEntity(pos);
        return te != null && te.hasCapability(cap, dir.getOpposite()) ? te.getCapability(cap, dir.getOpposite()) : null;
    }

    @Override
    public void handleGUIButtonPress(EntityPlayer player, int... data){
        switch(data[0]){
            case 0:
                hopperMode = HopperMode.values()[(hopperMode.ordinal() + 1) % HopperMode.values().length];
                break;
            case 1:
                interactEngine = !interactEngine;
                break;
        }
    }
}
