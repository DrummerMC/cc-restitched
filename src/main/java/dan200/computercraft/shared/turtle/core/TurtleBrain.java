/*
 * This file is part of ComputerCraft - http://www.computercraft.info
 * Copyright Daniel Ratcliffe, 2011-2021. Do not distribute without permission.
 * Send enquiries to dratcliffe@gmail.com
 */

package dan200.computercraft.shared.turtle.core;

import com.google.common.base.Objects;
import com.mojang.authlib.GameProfile;
import dan200.computercraft.ComputerCraft;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.turtle.*;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.shared.TurtleUpgrades;
import dan200.computercraft.shared.computer.blocks.ComputerProxy;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.turtle.blocks.TileTurtle;
import dan200.computercraft.shared.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static dan200.computercraft.shared.common.IColouredItem.NBT_COLOUR;
import static dan200.computercraft.shared.util.WaterloggableHelpers.WATERLOGGED;

public class TurtleBrain implements ITurtleAccess
{
    public static final String NBT_RIGHT_UPGRADE = "RightUpgrade";
    public static final String NBT_RIGHT_UPGRADE_DATA = "RightUpgradeNbt";
    public static final String NBT_LEFT_UPGRADE = "LeftUpgrade";
    public static final String NBT_LEFT_UPGRADE_DATA = "LeftUpgradeNbt";
    public static final String NBT_FUEL = "Fuel";
    public static final String NBT_OVERLAY = "Overlay";

    private static final String NBT_SLOT = "Slot";

    private static final int ANIM_DURATION = 8;
    private final Queue<TurtleCommandQueueEntry> commandQueue = new ArrayDeque<>();
    private final Map<TurtleSide, ITurtleUpgrade> upgrades = new EnumMap<>( TurtleSide.class );
    private final Map<TurtleSide, IPeripheral> peripherals = new EnumMap<>( TurtleSide.class );
    private final Map<TurtleSide, NbtCompound> upgradeNBTData = new EnumMap<>( TurtleSide.class );
    TurtlePlayer cachedPlayer;
    private TileTurtle owner;
    private final Inventory inventory = (InventoryDelegate) () -> owner;
    private ComputerProxy proxy;
    private GameProfile owningPlayer;
    private int commandsIssued = 0;
    private int selectedSlot = 0;
    private int fuelLevel = 0;
    private int colourHex = -1;
    private Identifier overlay = null;
    private TurtleAnimation animation = TurtleAnimation.NONE;
    private int animationProgress = 0;
    private int lastAnimationProgress = 0;

    public TurtleBrain( TileTurtle turtle )
    {
        owner = turtle;
    }

    public TileTurtle getOwner()
    {
        return owner;
    }

    public void setOwner( TileTurtle owner )
    {
        this.owner = owner;
    }

    public ComputerProxy getProxy()
    {
        if( proxy == null )
        {
            proxy = new ComputerProxy( () -> owner );
        }
        return proxy;
    }

    public ComputerFamily getFamily()
    {
        return owner.getFamily();
    }

    public void setupComputer( ServerComputer computer )
    {
        updatePeripherals( computer );
    }

    private void updatePeripherals( ServerComputer serverComputer )
    {
        if( serverComputer == null )
        {
            return;
        }

        // Update peripherals
        for( TurtleSide side : TurtleSide.values() )
        {
            ITurtleUpgrade upgrade = getUpgrade( side );
            IPeripheral peripheral = null;
            if( upgrade != null && upgrade.getType()
                .isPeripheral() )
            {
                peripheral = upgrade.createPeripheral( this, side );
            }

            IPeripheral existing = peripherals.get( side );
            if( existing == peripheral || (existing != null && peripheral != null && existing.equals( peripheral )) )
            {
                // If the peripheral is the same, just use that.
                peripheral = existing;
            }
            else
            {
                // Otherwise update our map
                peripherals.put( side, peripheral );
            }

            // Always update the computer: it may not be the same computer as before!
            serverComputer.setPeripheral( toDirection( side ), peripheral );
        }
    }

    private static ComputerSide toDirection( TurtleSide side )
    {
        switch( side )
        {
            case LEFT:
                return ComputerSide.LEFT;
            case RIGHT:
            default:
                return ComputerSide.RIGHT;
        }
    }

    public void update()
    {
        World world = getWorld();
        if( !world.isClient )
        {
            // Advance movement
            updateCommands();

            // The block may have been broken while the command was executing (for instance, if a block explodes
            // when being mined). If so, abort.
            if( owner.isRemoved() ) return;
        }

        // Advance animation
        updateAnimation();

        // Advance upgrades
        if( !upgrades.isEmpty() )
        {
            for( Map.Entry<TurtleSide, ITurtleUpgrade> entry : upgrades.entrySet() )
            {
                entry.getValue()
                    .update( this, entry.getKey() );
            }
        }
    }

    @Nonnull
    @Override
    public World getWorld()
    {
        return owner.getWorld();
    }

    @Nonnull
    @Override
    public BlockPos getPosition()
    {
        return owner.getPos();
    }

    @Override
    public boolean teleportTo( @Nonnull World world, @Nonnull BlockPos pos )
    {
        if( world.isClient || getWorld().isClient )
        {
            throw new UnsupportedOperationException( "Cannot teleport on the client" );
        }

        // Cache info about the old turtle (so we don't access this after we delete ourselves)
        World oldWorld = getWorld();
        TileTurtle oldOwner = owner;
        BlockPos oldPos = owner.getPos();
        BlockState oldBlock = owner.getCachedState();

        if( oldWorld == world && oldPos.equals( pos ) )
        {
            // Teleporting to the current position is a no-op
            return true;
        }

        // Ensure the chunk is loaded
        if( !world.isChunkLoaded( pos ) )
        {
            return false;
        }

        // Ensure we're inside the world border
        if( !world.getWorldBorder()
            .contains( pos ) )
        {
            return false;
        }

        FluidState existingFluid = world.getBlockState( pos )
            .getFluidState();
        BlockState newState = oldBlock
            // We only mark this as waterlogged when travelling into a source block. This prevents us from spreading
            // fluid by creating a new source when moving into a block, causing the next block to be almost full and
            // then moving into that.
            .with( WATERLOGGED, existingFluid.isIn( FluidTags.WATER ) && existingFluid.isStill() );

        oldOwner.notifyMoveStart();

        try
        {
            // Create a new turtle
            if( world.setBlockState( pos, newState, 0 ) )
            {
                Block block = world.getBlockState( pos )
                    .getBlock();
                if( block == oldBlock.getBlock() )
                {
                    BlockEntity newTile = world.getBlockEntity( pos );
                    if( newTile instanceof TileTurtle newTurtle )
                    {
                        // Copy the old turtle state into the new turtle
                        newTurtle.setWorld( world );
                        newTurtle.transferStateFrom( oldOwner );
                        newTurtle.createServerComputer()
                            .setWorld( world );
                        newTurtle.createServerComputer()
                            .setPosition( pos );

                        // Remove the old turtle
                        oldWorld.removeBlock( oldPos, false );

                        // Make sure everybody knows about it
                        newTurtle.updateBlock();
                        newTurtle.updateInput();
                        newTurtle.updateOutput();
                        return true;
                    }
                }

                // Something went wrong, remove the newly created turtle
                world.removeBlock( pos, false );
            }
        }
        finally
        {
            // whatever happens, unblock old turtle in case it's still in world
            oldOwner.notifyMoveEnd();
        }

        return false;
    }

    @Nonnull
    @Override
    public Vec3d getVisualPosition( float f )
    {
        Vec3d offset = getRenderOffset( f );
        BlockPos pos = owner.getPos();
        return new Vec3d( pos.getX() + 0.5 + offset.x, pos.getY() + 0.5 + offset.y, pos.getZ() + 0.5 + offset.z );
    }

    @Override
    public float getVisualYaw( float f )
    {
        float yaw = getDirection().asRotation();
        switch( animation )
        {
            case TURN_LEFT:
                yaw += 90.0f * (1.0f - getAnimationFraction( f ));
                if( yaw >= 360.0f )
                {
                    yaw -= 360.0f;
                }
                break;
            case TURN_RIGHT:
                yaw += -90.0f * (1.0f - getAnimationFraction( f ));
                if( yaw < 0.0f )
                {
                    yaw += 360.0f;
                }
                break;
        }
        return yaw;
    }

    @Nonnull
    @Override
    public Direction getDirection()
    {
        return owner.getDirection();
    }

    @Override
    public void setDirection( @Nonnull Direction dir )
    {
        owner.setDirection( dir );
    }

    @Override
    public int getSelectedSlot()
    {
        return selectedSlot;
    }

    @Override
    public void setSelectedSlot( int slot )
    {
        if( getWorld().isClient )
        {
            throw new UnsupportedOperationException( "Cannot set the slot on the client" );
        }

        if( slot >= 0 && slot < owner.size() )
        {
            selectedSlot = slot;
            owner.onTileEntityChange();
        }
    }

    @Override
    public int getColour()
    {
        return colourHex;
    }

    @Override
    public void setColour( int colour )
    {
        if( colour >= 0 && colour <= 0xFFFFFF )
        {
            if( colourHex != colour )
            {
                colourHex = colour;
                owner.updateBlock();
            }
        }
        else if( colourHex != -1 )
        {
            colourHex = -1;
            owner.updateBlock();
        }
    }

    @Nullable
    @Override
    public GameProfile getOwningPlayer()
    {
        return owningPlayer;
    }

    @Override
    public boolean isFuelNeeded()
    {
        return ComputerCraft.turtlesNeedFuel;
    }

    @Override
    public int getFuelLevel()
    {
        return Math.min( fuelLevel, getFuelLimit() );
    }

    @Override
    public void setFuelLevel( int level )
    {
        fuelLevel = Math.min( level, getFuelLimit() );
        owner.onTileEntityChange();
    }

    @Override
    public int getFuelLimit()
    {
        if( owner.getFamily() == ComputerFamily.ADVANCED )
        {
            return ComputerCraft.advancedTurtleFuelLimit;
        }
        else
        {
            return ComputerCraft.turtleFuelLimit;
        }
    }

    @Override
    public boolean consumeFuel( int fuel )
    {
        if( getWorld().isClient )
        {
            throw new UnsupportedOperationException( "Cannot consume fuel on the client" );
        }

        if( !isFuelNeeded() )
        {
            return true;
        }

        int consumption = Math.max( fuel, 0 );
        if( getFuelLevel() >= consumption )
        {
            setFuelLevel( getFuelLevel() - consumption );
            return true;
        }
        return false;
    }

    @Override
    public void addFuel( int fuel )
    {
        if( getWorld().isClient )
        {
            throw new UnsupportedOperationException( "Cannot add fuel on the client" );
        }

        int addition = Math.max( fuel, 0 );
        setFuelLevel( getFuelLevel() + addition );
    }

    @Nonnull
    @Override
    public MethodResult executeCommand( @Nonnull ITurtleCommand command )
    {
        if( getWorld().isClient )
        {
            throw new UnsupportedOperationException( "Cannot run commands on the client" );
        }
        if( commandQueue.size() > 16 ) return MethodResult.of( false, "Too many ongoing turtle commands" );

        // Issue command
        int commandID = issueCommand( command );
        return new CommandCallback( commandID ).pull;
    }

    private int issueCommand( ITurtleCommand command )
    {
        commandQueue.offer( new TurtleCommandQueueEntry( ++commandsIssued, command ) );
        return commandsIssued;
    }

    @Override
    public void playAnimation( @Nonnull TurtleAnimation animation )
    {
        if( getWorld().isClient )
        {
            throw new UnsupportedOperationException( "Cannot play animations on the client" );
        }

        this.animation = animation;
        if( this.animation == TurtleAnimation.SHORT_WAIT )
        {
            animationProgress = ANIM_DURATION / 2;
            lastAnimationProgress = ANIM_DURATION / 2;
        }
        else
        {
            animationProgress = 0;
            lastAnimationProgress = 0;
        }
        owner.updateBlock();
    }

    @Override
    public ITurtleUpgrade getUpgrade( @Nonnull TurtleSide side )
    {
        return upgrades.get( side );
    }

    @Override
    public void setUpgrade( @Nonnull TurtleSide side, ITurtleUpgrade upgrade )
    {
        // Remove old upgrade
        if( upgrades.containsKey( side ) )
        {
            if( upgrades.get( side ) == upgrade )
            {
                return;
            }
            upgrades.remove( side );
        }
        else
        {
            if( upgrade == null )
            {
                return;
            }
        }

        upgradeNBTData.remove( side );

        // Set new upgrade
        if( upgrade != null )
        {
            upgrades.put( side, upgrade );
        }

        // Notify clients and create peripherals
        if( owner.getWorld() != null )
        {
            updatePeripherals( owner.createServerComputer() );
            owner.updateBlock();
        }
    }

    @Override
    public IPeripheral getPeripheral( @Nonnull TurtleSide side )
    {
        return peripherals.get( side );
    }

    @Nonnull
    @Override
    public NbtCompound getUpgradeNBTData( TurtleSide side )
    {
        NbtCompound nbt = upgradeNBTData.get( side );
        if( nbt == null )
        {
            upgradeNBTData.put( side, nbt = new NbtCompound() );
        }
        return nbt;
    }

    @Override
    public void updateUpgradeNBTData( @Nonnull TurtleSide side )
    {
        owner.updateBlock();
    }

    @Nonnull
    @Override
    public Inventory getInventory()
    {
        return inventory;
    }

    public void setOwningPlayer( GameProfile profile )
    {
        owningPlayer = profile;
    }

    private void updateCommands()
    {
        if( animation != TurtleAnimation.NONE || commandQueue.isEmpty() )
        {
            return;
        }

        // If we've got a computer, ensure that we're allowed to perform work.
        ServerComputer computer = owner.getServerComputer();
        if( computer != null && !computer.getComputer()
            .getMainThreadMonitor()
            .canWork() )
        {
            return;
        }

        // Pull a new command
        TurtleCommandQueueEntry nextCommand = commandQueue.poll();
        if( nextCommand == null )
        {
            return;
        }

        // Execute the command
        long start = System.nanoTime();
        TurtleCommandResult result = nextCommand.command.execute( this );
        long end = System.nanoTime();

        // Dispatch the callback
        if( computer == null )
        {
            return;
        }
        computer.getComputer()
            .getMainThreadMonitor()
            .trackWork( end - start, TimeUnit.NANOSECONDS );
        int callbackID = nextCommand.callbackID;
        if( callbackID < 0 )
        {
            return;
        }

        if( result != null && result.isSuccess() )
        {
            Object[] results = result.getResults();
            if( results != null )
            {
                Object[] arguments = new Object[results.length + 2];
                arguments[0] = callbackID;
                arguments[1] = true;
                System.arraycopy( results, 0, arguments, 2, results.length );
                computer.queueEvent( "turtle_response", arguments );
            }
            else
            {
                computer.queueEvent( "turtle_response", new Object[] {
                    callbackID,
                    true,
                } );
            }
        }
        else
        {
            computer.queueEvent( "turtle_response", new Object[] {
                callbackID,
                false,
                result != null ? result.getErrorMessage() : null,
            } );
        }
    }

    private void updateAnimation()
    {
        if( animation != TurtleAnimation.NONE )
        {
            World world = getWorld();

            if( ComputerCraft.turtlesCanPush )
            {
                // Advance entity pushing
                if( animation == TurtleAnimation.MOVE_FORWARD || animation == TurtleAnimation.MOVE_BACK || animation == TurtleAnimation.MOVE_UP || animation == TurtleAnimation.MOVE_DOWN )
                {
                    BlockPos pos = getPosition();
                    Direction moveDir;
                    switch( animation )
                    {
                        case MOVE_FORWARD:
                        default:
                            moveDir = getDirection();
                            break;
                        case MOVE_BACK:
                            moveDir = getDirection().getOpposite();
                            break;
                        case MOVE_UP:
                            moveDir = Direction.UP;
                            break;
                        case MOVE_DOWN:
                            moveDir = Direction.DOWN;
                            break;
                    }

                    double minX = pos.getX();
                    double minY = pos.getY();
                    double minZ = pos.getZ();
                    double maxX = minX + 1.0;
                    double maxY = minY + 1.0;
                    double maxZ = minZ + 1.0;

                    float pushFrac = 1.0f - (float) (animationProgress + 1) / ANIM_DURATION;
                    float push = Math.max( pushFrac + 0.0125f, 0.0f );
                    if( moveDir.getOffsetX() < 0 )
                    {
                        minX += moveDir.getOffsetX() * push;
                    }
                    else
                    {
                        maxX -= moveDir.getOffsetX() * push;
                    }

                    if( moveDir.getOffsetY() < 0 )
                    {
                        minY += moveDir.getOffsetY() * push;
                    }
                    else
                    {
                        maxY -= moveDir.getOffsetY() * push;
                    }

                    if( moveDir.getOffsetZ() < 0 )
                    {
                        minZ += moveDir.getOffsetZ() * push;
                    }
                    else
                    {
                        maxZ -= moveDir.getOffsetZ() * push;
                    }

                    Box aabb = new Box( minX, minY, minZ, maxX, maxY, maxZ );
                    List<Entity> list = world.getEntitiesByClass( Entity.class, aabb, EntityPredicates.EXCEPT_SPECTATOR );
                    if( !list.isEmpty() )
                    {
                        double pushStep = 1.0f / ANIM_DURATION;
                        double pushStepX = moveDir.getOffsetX() * pushStep;
                        double pushStepY = moveDir.getOffsetY() * pushStep;
                        double pushStepZ = moveDir.getOffsetZ() * pushStep;
                        for( Entity entity : list )
                        {
                            entity.move( MovementType.PISTON, new Vec3d( pushStepX, pushStepY, pushStepZ ) );
                        }
                    }
                }
            }

            // Advance valentines day easter egg
            if( world.isClient && animation == TurtleAnimation.MOVE_FORWARD && animationProgress == 4 )
            {
                // Spawn love pfx if valentines day
                Holiday currentHoliday = HolidayUtil.getCurrentHoliday();
                if( currentHoliday == Holiday.VALENTINES )
                {
                    Vec3d position = getVisualPosition( 1.0f );
                    if( position != null )
                    {
                        double x = position.x + world.random.nextGaussian() * 0.1;
                        double y = position.y + 0.5 + world.random.nextGaussian() * 0.1;
                        double z = position.z + world.random.nextGaussian() * 0.1;
                        world.addParticle( ParticleTypes.HEART,
                            x,
                            y,
                            z,
                            world.random.nextGaussian() * 0.02,
                            world.random.nextGaussian() * 0.02,
                            world.random.nextGaussian() * 0.02 );
                    }
                }
            }

            // Wait for anim completion
            lastAnimationProgress = animationProgress;
            if( ++animationProgress >= ANIM_DURATION )
            {
                animation = TurtleAnimation.NONE;
                animationProgress = 0;
                lastAnimationProgress = 0;
            }
        }
    }

    public Vec3d getRenderOffset( float f )
    {
        switch( animation )
        {
            case MOVE_FORWARD:
            case MOVE_BACK:
            case MOVE_UP:
            case MOVE_DOWN:
                // Get direction
                Direction dir;
                switch( animation )
                {
                    case MOVE_FORWARD:
                    default:
                        dir = getDirection();
                        break;
                    case MOVE_BACK:
                        dir = getDirection().getOpposite();
                        break;
                    case MOVE_UP:
                        dir = Direction.UP;
                        break;
                    case MOVE_DOWN:
                        dir = Direction.DOWN;
                        break;
                }

                double distance = -1.0 + getAnimationFraction( f );
                return new Vec3d( distance * dir.getOffsetX(), distance * dir.getOffsetY(), distance * dir.getOffsetZ() );
            default:
                return Vec3d.ZERO;
        }
    }

    private float getAnimationFraction( float f )
    {
        float next = (float) animationProgress / ANIM_DURATION;
        float previous = (float) lastAnimationProgress / ANIM_DURATION;
        return previous + (next - previous) * f;
    }

    public void readFromNBT( NbtCompound nbt )
    {
        readCommon( nbt );

        // Read state
        selectedSlot = nbt.getInt( NBT_SLOT );

        // Read owner
        if( nbt.contains( "Owner", NBTUtil.TAG_COMPOUND ) )
        {
            NbtCompound owner = nbt.getCompound( "Owner" );
            owningPlayer = new GameProfile( new UUID( owner.getLong( "UpperId" ), owner.getLong( "LowerId" ) ), owner.getString( "Name" ) );
        }
        else
        {
            owningPlayer = null;
        }
    }

    /**
     * Read common data for saving and client synchronisation.
     *
     * @param nbt The tag to read from
     */
    private void readCommon( NbtCompound nbt )
    {
        // Read fields
        colourHex = nbt.contains( NBT_COLOUR ) ? nbt.getInt( NBT_COLOUR ) : -1;
        fuelLevel = nbt.contains( NBT_FUEL ) ? nbt.getInt( NBT_FUEL ) : 0;
        overlay = nbt.contains( NBT_OVERLAY ) ? new Identifier( nbt.getString( NBT_OVERLAY ) ) : null;

        // Read upgrades
        setUpgrade( TurtleSide.LEFT, nbt.contains( NBT_LEFT_UPGRADE ) ? TurtleUpgrades.get( nbt.getString( NBT_LEFT_UPGRADE ) ) : null );
        setUpgrade( TurtleSide.RIGHT, nbt.contains( NBT_RIGHT_UPGRADE ) ? TurtleUpgrades.get( nbt.getString( NBT_RIGHT_UPGRADE ) ) : null );

        // NBT
        upgradeNBTData.clear();
        if( nbt.contains( NBT_LEFT_UPGRADE_DATA ) )
        {
            upgradeNBTData.put( TurtleSide.LEFT,
                nbt.getCompound( NBT_LEFT_UPGRADE_DATA )
                    .copy() );
        }
        if( nbt.contains( NBT_RIGHT_UPGRADE_DATA ) )
        {
            upgradeNBTData.put( TurtleSide.RIGHT,
                nbt.getCompound( NBT_RIGHT_UPGRADE_DATA )
                    .copy() );
        }
    }

    public NbtCompound writeToNBT( NbtCompound nbt )
    {
        writeCommon( nbt );

        // Write state
        nbt.putInt( NBT_SLOT, selectedSlot );

        // Write owner
        if( owningPlayer != null )
        {
            NbtCompound owner = new NbtCompound();
            nbt.put( "Owner", owner );

            owner.putLong( "UpperId", owningPlayer.getId()
                .getMostSignificantBits() );
            owner.putLong( "LowerId", owningPlayer.getId()
                .getLeastSignificantBits() );
            owner.putString( "Name", owningPlayer.getName() );
        }

        return nbt;
    }

    private void writeCommon( NbtCompound nbt )
    {
        nbt.putInt( NBT_FUEL, fuelLevel );
        if( colourHex != -1 )
        {
            nbt.putInt( NBT_COLOUR, colourHex );
        }
        if( overlay != null )
        {
            nbt.putString( NBT_OVERLAY, overlay.toString() );
        }

        // Write upgrades
        String leftUpgradeId = getUpgradeId( getUpgrade( TurtleSide.LEFT ) );
        if( leftUpgradeId != null )
        {
            nbt.putString( NBT_LEFT_UPGRADE, leftUpgradeId );
        }
        String rightUpgradeId = getUpgradeId( getUpgrade( TurtleSide.RIGHT ) );
        if( rightUpgradeId != null )
        {
            nbt.putString( NBT_RIGHT_UPGRADE, rightUpgradeId );
        }

        // Write upgrade NBT
        if( upgradeNBTData.containsKey( TurtleSide.LEFT ) )
        {
            nbt.put( NBT_LEFT_UPGRADE_DATA,
                getUpgradeNBTData( TurtleSide.LEFT ).copy() );
        }
        if( upgradeNBTData.containsKey( TurtleSide.RIGHT ) )
        {
            nbt.put( NBT_RIGHT_UPGRADE_DATA,
                getUpgradeNBTData( TurtleSide.RIGHT ).copy() );
        }
    }

    private static String getUpgradeId( ITurtleUpgrade upgrade )
    {
        return upgrade != null ? upgrade.getUpgradeID()
            .toString() : null;
    }

    public void readDescription( NbtCompound nbt )
    {
        readCommon( nbt );

        // Animation
        TurtleAnimation anim = TurtleAnimation.values()[nbt.getInt( "Animation" )];
        if( anim != animation && anim != TurtleAnimation.WAIT && anim != TurtleAnimation.SHORT_WAIT && anim != TurtleAnimation.NONE )
        {
            animation = anim;
            animationProgress = 0;
            lastAnimationProgress = 0;
        }
    }

    public void writeDescription( NbtCompound nbt )
    {
        writeCommon( nbt );
        nbt.putInt( "Animation", animation.ordinal() );
    }

    public Identifier getOverlay()
    {
        return overlay;
    }

    public void setOverlay( Identifier overlay )
    {
        if( !Objects.equal( this.overlay, overlay ) )
        {
            this.overlay = overlay;
            owner.updateBlock();
        }
    }

    public DyeColor getDyeColour()
    {
        if( colourHex == -1 )
        {
            return null;
        }
        Colour colour = Colour.fromHex( colourHex );
        return colour == null ? null : DyeColor.byId( 15 - colour.ordinal() );
    }

    public void setDyeColour( DyeColor dyeColour )
    {
        int newColour = -1;
        if( dyeColour != null )
        {
            newColour = Colour.values()[15 - dyeColour.getId()].getHex();
        }
        if( colourHex != newColour )
        {
            colourHex = newColour;
            owner.updateBlock();
        }
    }

    public float getToolRenderAngle( TurtleSide side, float f )
    {
        return (side == TurtleSide.LEFT && animation == TurtleAnimation.SWING_LEFT_TOOL) || (side == TurtleSide.RIGHT && animation == TurtleAnimation.SWING_RIGHT_TOOL) ? 45.0f * (float) Math.sin(
            getAnimationFraction( f ) * Math.PI ) : 0.0f;
    }

    private static final class CommandCallback implements ILuaCallback
    {
        final MethodResult pull = MethodResult.pullEvent( "turtle_response", this );
        private final int command;

        CommandCallback( int command )
        {
            this.command = command;
        }

        @Nonnull
        @Override
        public MethodResult resume( Object[] response )
        {
            if( response.length < 3 || !(response[1] instanceof Number id) || !(response[2] instanceof Boolean) )
            {
                return pull;
            }

            if( id.intValue() != command ) return pull;

            return MethodResult.of( Arrays.copyOfRange( response, 2, response.length ) );
        }
    }
}
