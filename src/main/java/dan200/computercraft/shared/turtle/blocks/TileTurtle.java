/*
 * This file is part of ComputerCraft - http://www.computercraft.info
 * Copyright Daniel Ratcliffe, 2011-2021. Do not distribute without permission.
 * Send enquiries to dratcliffe@gmail.com
 */

package dan200.computercraft.shared.turtle.blocks;

import com.mojang.authlib.GameProfile;
import dan200.computercraft.ComputerCraft;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.ITurtleUpgrade;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.shared.common.TileGeneric;
import dan200.computercraft.shared.computer.blocks.ComputerProxy;
import dan200.computercraft.shared.computer.blocks.TileComputerBase;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ComputerState;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.turtle.apis.TurtleAPI;
import dan200.computercraft.shared.turtle.core.TurtleBrain;
import dan200.computercraft.shared.turtle.inventory.ContainerTurtle;
import dan200.computercraft.shared.util.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.DyeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;

public class TileTurtle extends TileComputerBase implements ITurtleTile, DefaultInventory
{
    public static final int INVENTORY_SIZE = 16;
    public static final int INVENTORY_WIDTH = 4;
    public static final int INVENTORY_HEIGHT = 4;
    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize( INVENTORY_SIZE, ItemStack.EMPTY );
    private final DefaultedList<ItemStack> previousInventory = DefaultedList.ofSize( INVENTORY_SIZE, ItemStack.EMPTY );
    private boolean inventoryChanged = false;
    private TurtleBrain brain = new TurtleBrain( this );
    private MoveState moveState = MoveState.NOT_MOVED;

    public TileTurtle( BlockEntityType<? extends TileGeneric> type, ComputerFamily family )
    {
        super( type, family );
    }

    @Override
    protected void unload()
    {
        if( !this.hasMoved() )
        {
            super.unload();
        }
    }

    @Override
    public void destroy()
    {
        if( !this.hasMoved() )
        {
            // Stop computer
            super.destroy();

            // Drop contents
            if( !this.getWorld().isClient )
            {
                int size = this.size();
                for( int i = 0; i < size; i++ )
                {
                    ItemStack stack = this.getStack( i );
                    if( !stack.isEmpty() )
                    {
                        WorldUtil.dropItemStack( stack, this.getWorld(), this.getPos() );
                    }
                }
            }
        }
        else
        {
            // Just turn off any redstone we had on
            for( Direction dir : DirectionUtil.FACINGS )
            {
                RedstoneUtil.propagateRedstoneOutput( this.getWorld(), this.getPos(), dir );
            }
        }
    }

    private boolean hasMoved()
    {
        return this.moveState == MoveState.MOVED;
    }

    @Override
    public int size()
    {
        return INVENTORY_SIZE;
    }

    @Override
    public boolean isEmpty()
    {
        for( ItemStack stack : this.inventory )
        {
            if( !stack.isEmpty() )
            {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    @Override
    public ItemStack getStack( int slot )
    {
        return slot >= 0 && slot < INVENTORY_SIZE ? this.inventory.get( slot ) : ItemStack.EMPTY;
    }

    @Nonnull
    @Override
    public ItemStack removeStack( int slot, int count )
    {
        if( count == 0 )
        {
            return ItemStack.EMPTY;
        }

        ItemStack stack = this.getStack( slot );
        if( stack.isEmpty() )
        {
            return ItemStack.EMPTY;
        }

        if( stack.getCount() <= count )
        {
            this.setStack( slot, ItemStack.EMPTY );
            return stack;
        }

        ItemStack part = stack.split( count );
        this.onInventoryDefinitelyChanged();
        return part;
    }

    @Nonnull
    @Override
    public ItemStack removeStack( int slot )
    {
        ItemStack result = this.getStack( slot );
        this.setStack( slot, ItemStack.EMPTY );
        return result;
    }

    @Override
    public void setStack( int i, @Nonnull ItemStack stack )
    {
        if( i >= 0 && i < INVENTORY_SIZE && !InventoryUtil.areItemsEqual( stack, this.inventory.get( i ) ) )
        {
            this.inventory.set( i, stack );
            this.onInventoryDefinitelyChanged();
        }
    }

    @Override
    public boolean canPlayerUse( @Nonnull PlayerEntity player )
    {
        return this.isUsable( player, false );
    }

    private void onInventoryDefinitelyChanged()
    {
        super.markDirty();
        this.inventoryChanged = true;
    }

    @Override
    protected boolean canNameWithTag( PlayerEntity player )
    {
        return true;
    }

    @Nonnull
    @Override
    public ActionResult onActivate( PlayerEntity player, Hand hand, BlockHitResult hit )
    {
        // Apply dye
        ItemStack currentItem = player.getStackInHand( hand );
        if( !currentItem.isEmpty() )
        {
            if( currentItem.getItem() instanceof DyeItem )
            {
                // Dye to change turtle colour
                if( !this.getWorld().isClient )
                {
                    DyeColor dye = ((DyeItem) currentItem.getItem()).getColor();
                    if( this.brain.getDyeColour() != dye )
                    {
                        this.brain.setDyeColour( dye );
                        if( !player.isCreative() )
                        {
                            currentItem.decrement( 1 );
                        }
                    }
                }
                return ActionResult.SUCCESS;
            }
            else if( currentItem.getItem() == Items.WATER_BUCKET && this.brain.getColour() != -1 )
            {
                // Water to remove turtle colour
                if( !this.getWorld().isClient )
                {
                    if( this.brain.getColour() != -1 )
                    {
                        this.brain.setColour( -1 );
                        if( !player.isCreative() )
                        {
                            player.setStackInHand( hand, new ItemStack( Items.BUCKET ) );
                            player.inventory.markDirty();
                        }
                    }
                }
                return ActionResult.SUCCESS;
            }
        }

        // Open GUI or whatever
        return super.onActivate( player, hand, hit );
    }

    @Override
    public void onNeighbourChange( @Nonnull BlockPos neighbour )
    {
        if( this.moveState == MoveState.NOT_MOVED )
        {
            super.onNeighbourChange( neighbour );
        }
    }

    @Override
    public void onNeighbourTileEntityChange( @Nonnull BlockPos neighbour )
    {
        if( this.moveState == MoveState.NOT_MOVED )
        {
            super.onNeighbourTileEntityChange( neighbour );
        }
    }

    @Override
    public void tick()
    {
        super.tick();
        this.brain.update();
        if( !this.getWorld().isClient && this.inventoryChanged )
        {
            ServerComputer computer = this.getServerComputer();
            if( computer != null )
            {
                computer.queueEvent( "turtle_inventory" );
            }

            this.inventoryChanged = false;
            for( int n = 0; n < this.size(); n++ )
            {
                this.previousInventory.set( n,
                    this.getStack( n ).copy() );
            }
        }
    }

    @Override
    protected void updateBlockState( ComputerState newState )
    {
    }

    @Nonnull
    @Override
    public CompoundTag toTag( @Nonnull CompoundTag nbt )
    {
        // Write inventory
        ListTag nbttaglist = new ListTag();
        for( int i = 0; i < INVENTORY_SIZE; i++ )
        {
            if( !this.inventory.get( i )
                .isEmpty() )
            {
                CompoundTag tag = new CompoundTag();
                tag.putByte( "Slot", (byte) i );
                this.inventory.get( i )
                    .toTag( tag );
                nbttaglist.add( tag );
            }
        }
        nbt.put( "Items", nbttaglist );

        // Write brain
        nbt = this.brain.writeToNBT( nbt );

        return super.toTag( nbt );
    }

    // IDirectionalTile

    @Override
    public void fromTag( @Nonnull BlockState state, @Nonnull CompoundTag nbt )
    {
        super.fromTag( state, nbt );

        // Read inventory
        ListTag nbttaglist = nbt.getList( "Items", NBTUtil.TAG_COMPOUND );
        this.inventory.clear();
        this.previousInventory.clear();
        for( int i = 0; i < nbttaglist.size(); i++ )
        {
            CompoundTag tag = nbttaglist.getCompound( i );
            int slot = tag.getByte( "Slot" ) & 0xff;
            if( slot < this.size() )
            {
                this.inventory.set( slot, ItemStack.fromTag( tag ) );
                this.previousInventory.set( slot, this.inventory.get( slot )
                    .copy() );
            }
        }

        // Read state
        this.brain.readFromNBT( nbt );
    }

    @Override
    protected boolean isPeripheralBlockedOnSide( ComputerSide localSide )
    {
        return this.hasPeripheralUpgradeOnSide( localSide );
    }

    // ITurtleTile

    @Override
    public Direction getDirection()
    {
        return this.getCachedState().get( BlockTurtle.FACING );
    }

    @Override
    protected ServerComputer createComputer( int instanceID, int id )
    {
        ServerComputer computer = new ServerComputer( this.getWorld(),
            id, this.label,
            instanceID, this.getFamily(),
            ComputerCraft.turtleTermWidth,
            ComputerCraft.turtleTermHeight );
        computer.setPosition( this.getPos() );
        computer.addAPI( new TurtleAPI( computer.getAPIEnvironment(), this.getAccess() ) );
        this.brain.setupComputer( computer );
        return computer;
    }

    @Override
    protected void writeDescription( @Nonnull CompoundTag nbt )
    {
        super.writeDescription( nbt );
        this.brain.writeDescription( nbt );
    }

    @Override
    protected void readDescription( @Nonnull CompoundTag nbt )
    {
        super.readDescription( nbt );
        this.brain.readDescription( nbt );
    }

    @Override
    public ComputerProxy createProxy()
    {
        return this.brain.getProxy();
    }

    public void setDirection( Direction dir )
    {
        if( dir.getAxis() == Direction.Axis.Y )
        {
            dir = Direction.NORTH;
        }
        this.world.setBlockState( this.pos,
            this.getCachedState().with( BlockTurtle.FACING, dir ) );
        this.updateOutput();
        this.updateInput();
        this.onTileEntityChange();
    }

    public void onTileEntityChange()
    {
        super.markDirty();
    }

    private boolean hasPeripheralUpgradeOnSide( ComputerSide side )
    {
        ITurtleUpgrade upgrade;
        switch( side )
        {
            case RIGHT:
                upgrade = this.getUpgrade( TurtleSide.RIGHT );
                break;
            case LEFT:
                upgrade = this.getUpgrade( TurtleSide.LEFT );
                break;
            default:
                return false;
        }
        return upgrade != null && upgrade.getType()
            .isPeripheral();
    }

    // IInventory

    @Override
    protected double getInteractRange( PlayerEntity player )
    {
        return 12.0;
    }

    public void notifyMoveStart()
    {
        if( this.moveState == MoveState.NOT_MOVED )
        {
            this.moveState = MoveState.IN_PROGRESS;
        }
    }

    public void notifyMoveEnd()
    {
        // MoveState.MOVED is final
        if( this.moveState == MoveState.IN_PROGRESS )
        {
            this.moveState = MoveState.NOT_MOVED;
        }
    }

    @Override
    public int getColour()
    {
        return this.brain.getColour();
    }

    @Override
    public Identifier getOverlay()
    {
        return this.brain.getOverlay();
    }

    @Override
    public ITurtleUpgrade getUpgrade( TurtleSide side )
    {
        return this.brain.getUpgrade( side );
    }

    @Override
    public ITurtleAccess getAccess()
    {
        return this.brain;
    }

    @Override
    public Vec3d getRenderOffset( float f )
    {
        return this.brain.getRenderOffset( f );
    }

    @Override
    public float getRenderYaw( float f )
    {
        return this.brain.getVisualYaw( f );
    }

    @Override
    public float getToolRenderAngle( TurtleSide side, float f )
    {
        return this.brain.getToolRenderAngle( side, f );
    }

    void setOwningPlayer( GameProfile player )
    {
        this.brain.setOwningPlayer( player );
        this.markDirty();
    }

    // Networking stuff

    @Override
    public void markDirty()
    {
        super.markDirty();
        if( !this.inventoryChanged )
        {
            for( int n = 0; n < this.size(); n++ )
            {
                if( !ItemStack.areEqual( this.getStack( n ), this.previousInventory.get( n ) ) )
                {
                    this.inventoryChanged = true;
                    break;
                }
            }
        }
    }

    @Override
    public void clear()
    {
        boolean changed = false;
        for( int i = 0; i < INVENTORY_SIZE; i++ )
        {
            if( !this.inventory.get( i )
                .isEmpty() )
            {
                this.inventory.set( i, ItemStack.EMPTY );
                changed = true;
            }
        }

        if( changed )
        {
            this.onInventoryDefinitelyChanged();
        }
    }

    // Privates

    public void transferStateFrom( TileTurtle copy )
    {
        super.transferStateFrom( copy );
        Collections.copy( this.inventory, copy.inventory );
        Collections.copy( this.previousInventory, copy.previousInventory );
        this.inventoryChanged = copy.inventoryChanged;
        this.brain = copy.brain;
        this.brain.setOwner( this );

        // Mark the other turtle as having moved, and so its peripheral is dead.
        copy.moveState = MoveState.MOVED;
    }

    @Nullable
    @Override
    public ScreenHandler createMenu( int id, @Nonnull PlayerInventory inventory, @Nonnull PlayerEntity player )
    {
        return new ContainerTurtle( id, inventory, this.brain );
    }

    enum MoveState
    {
        NOT_MOVED, IN_PROGRESS, MOVED
    }
}
